# ADR-002: Configuration Design

**Status:** Accepted

---

## Context

The application has three categories of runtime configuration: Kubernetes connection details, GitLab access, and inbound security. These needed to be bound from `application.yaml` into typed Java classes so the rest of the code can access them without string-based `@Value` lookups scattered everywhere.

Several decisions were bundled here:

1. How to bind the config (annotation choice)
2. Which Lombok annotation to use on config classes
3. Where to store the GitLab access token
4. Whether timeouts should be hardcoded or configurable

---

## Decision

### 1. `@ConfigurationProperties` over `@Value`

`@ConfigurationProperties(prefix = "config")` is used on `AppConfig`. This binds the entire `config.*` tree into a single typed object graph.

`@Value("${config.kubernetes.namespace}")` scattered across classes was the alternative. It was rejected because:
- It creates hidden dependencies (a class silently requires a config key to exist at startup, with no obvious connection between the field and where it comes from)
- Refactoring a key name requires a global search
- `@ConfigurationProperties` validates the entire config block at startup and gives a clear error with the missing key name

### 2. `@Getter @Setter` instead of `@Data` on config classes

`@Data` was on the original config classes. It was replaced with `@Getter @Setter`.

`@Data` generates: `@Getter`, `@Setter`, `@ToString`, `@EqualsAndHashCode`, `@RequiredArgsConstructor`.

The problem is `@ToString`. `AppConfig.Security` holds `apiKey`. `AppConfig.GitLab.Auth` holds `token`. With `@Data`, any call to `.toString()` — which logging frameworks do automatically — would print these values in plaintext into the application logs.

`@Getter @Setter` gives Spring exactly what it needs for property binding (setters to inject values, getters for code to read them) without generating the unsafe `toString`.

`@EqualsAndHashCode` is also useless on a singleton config bean — config objects are never compared to each other.

### 3. Java Records were considered but not used for config

Records are immutable. `@ConfigurationProperties` in Spring Boot 3.x supports constructor-binding for records, which would eliminate setters entirely. This is appealing.

The reason records were not used: **nested config classes with default values**. For example:

```java
// This works in a mutable class
private int connectTimeoutMs = 10000;

// In a record, you cannot declare a field with a default value
// You would need @DefaultValue annotation or a custom constructor
public record Kubernetes(
    String environment,
    @DefaultValue("10000") int connectTimeoutMs,
    ...
) {}
```

`@DefaultValue` only works for simple scalar types and requires the annotation on every defaulted field across every nested level. With three levels of nesting (`AppConfig` → `Kubernetes` → `Helm`) and multiple defaults, this becomes verbose and fragile. Mutable classes with field-level defaults are simpler here.

Records remain the right call for `GitLabSession` and `ErrorResponse`, which are created in code (not bound from config) and benefit from immutability.

### 4. GitLab token moved to `config.gitlab.auth.token`

The original code stored the GitLab token under `config.kubernetes.helm.gitlabToken`. This was wrong conceptually: the token is a GitLab authentication credential, not a Kubernetes or Helm concern.

The correct location is `config.gitlab.auth.token`, which maps to `AppConfig.GitLab.Auth.token`. This also matches how the token is used in `GitLabClient`, which reads it from `appConfig.getGitLab().getAuth().getToken()`.

### 5. Timeouts externalised to config

`K8sConfig` previously had hardcoded values:
```java
client.setConnectTimeout(10000);
client.setReadTimeout(30000);
client.setWriteTimeout(30000);
```

These are now in `application.yaml` under `config.kubernetes.connect-timeout-ms` etc. with the same values as defaults.

The reason: in production environments with slower networks or larger job payloads, these values may need tuning without a code change and redeploy. Externalising them costs nothing (the defaults are still there) and avoids a redeploy for what is essentially an operational parameter.

---

## Alternatives Considered

### `@Configuration` + `@Bean` methods returning config values
Used in older Spring codebases. Verbose and gives no validation at startup. Rejected.

### Environment-specific YAML profiles (`application-uat.yaml`, `application-prod.yaml`)
Spring supports `spring.profiles.active` to load profile-specific files. This was considered for the values layering but rejected because the layering logic (env + region) is application-specific, not Spring-profile-specific. The layering is handled in `GitLabClient.fetchAndMergeValues()` instead.

---

## Consequences

- Any new config key must be added to `AppConfig` as a typed field, not accessed via `@Value`. This is enforced by convention, not by a compiler check.
- Secrets (`apiKey`, `token`) will never appear in `toString()` output.
- Changing a config key name is a two-step change: rename in `AppConfig` and rename in `application.yaml`. The compiler catches the Java side; the YAML side is only caught at startup.
