# ADR-006: GitLab Client Design

**Status:** Accepted

---

## Context

The application needs to read files from a private GitLab repository: job template YAML files, a base `values.yaml`, and optional environment/region-specific values files. This requires authenticated HTTP calls to the GitLab REST API.

Several design questions arose:
- How to initialise the HTTP client and build the API URLs
- How to handle the connection state (session) between requests
- How to pass URI variables to avoid positional substitution bugs
- Where to put the `GitLabSession` type

---

## Decision

### 1. `GitLabSession` as a top-level record in `gitlab/`

`GitLabSession` was originally a nested `public record` inside `GitLabUtils`. It was extracted to its own file `gitlab/GitLabSession.java`.

A `public` type should never be nested inside another class unless it is conceptually subordinate to that class and has no meaning outside it. `GitLabSession` is a first-class concept — it represents a live connection context to a GitLab repository. It is returned from `getSession()` and used directly in `JobService`. Making it a standalone type makes it navigable, referenceable, and independently documentable.

```java
public record GitLabSession(
    String baseUrl,
    String projectPath,
    String branch,
    String directory,
    RestClient client,
    String repositoryTreeUrl,
    String repositoryRawFileUrl
) {}
```

A record is used because `GitLabSession` is immutable — once built from the config, none of its fields should ever change. Records enforce this and provide `equals`, `hashCode`, and `toString` for free.

### 2. `@PostConstruct` for fail-fast session initialisation

```java
@PostConstruct
void initSession() {
    this.session = buildSession();
}
```

Session building parses the `HELM_REPO_URL` as a URI. If this URL is malformed, `new URI(...)` throws `URISyntaxException`. By building the session at `@PostConstruct`, this failure happens at application startup — the application refuses to start rather than failing silently on the first request at runtime.

This is the "fail fast" principle: surface configuration errors immediately, not lazily. An operator running the service sees the startup failure with a clear error message, rather than discovering the misconfiguration hours later via a `500` from a caller.

### 3. `RestClient` (synchronous, Spring 6)

Spring 6 / Spring Boot 3 introduced `RestClient` as the modern synchronous HTTP client, replacing `RestTemplate`. It has a fluent builder API and integrates naturally with Spring's type system.

`RestClient` is built once in `buildSession()` and stored inside `GitLabSession`. The authentication header (`PRIVATE-TOKEN: <token>`) is set as a default header on the client builder, so it is included automatically on every request without repeating it at each call site.

### 4. Named URI variables via `Map.of()`

Spring `RestClient` supports two forms of URI variable substitution:

```java
// Positional — maps by argument order
client.get().uri("...?path={path}&ref={ref}", directory, branch)

// Named — maps by name
client.get().uri("...?path={path}&ref={ref}", Map.of("path", directory, "ref", branch))
```

The original code used positional substitution with an extra argument (`projectPath`) that had no corresponding template variable. This was a silent bug — the extra argument was ignored, but the code read as if it was doing something meaningful.

Named substitution via `Map.of("path", directory, "ref", branch)` was adopted for all calls. The names in the map must match the `{name}` placeholders in the template — any mismatch is immediately obvious. Reordering the map entries has no effect. This makes the intent clear and eliminates the class of bug where reordering arguments silently changes which variable gets which value.

### 5. Path traversal guard on template names

```java
if (templateName == null || templateName.contains("..") || templateName.contains("/")) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid template name: ...");
}
```

`fetchTemplate` concatenates `templateName` into a file path: `jobs/` + templateName. Without a guard, a caller could pass `../../etc/passwd` or `../secrets.yaml` as the template name, causing the application to fetch arbitrary files from the repository.

The guard rejects any template name containing `..` (parent directory traversal) or `/` (subdirectory traversal). Valid template names are simple filenames like `loaderholiday.yaml`.

### 6. `generateJobNameSuffix()` inlined into `GitLabClient`

The original `JobUtils.generateJobNameSuffix()` was a one-method utility class used exclusively by `GitLabUtils` (now `GitLabClient`). Rather than keeping a dedicated class for a single private helper, the method was inlined as a `private static` method in `GitLabClient`.

The rule applied: a utility class exists to share code between multiple callers. When there is only one caller, the "utility" is just a helper that belongs to that caller.

---

## Alternatives Considered

### A: `WebClient` (reactive/non-blocking)
Spring's `WebClient` is the reactive alternative to `RestClient`. It supports non-blocking I/O and integrates with Project Reactor.

For this use case (synchronous request processing, no streaming, no reactive pipeline elsewhere), `WebClient` adds complexity without benefit. The thread blocks while waiting for GitLab responses either way — the question is whether to block a thread pool thread or a reactive scheduler thread. With no reactive chain to plug into, `WebClient` would require `.block()` calls everywhere, which defeats the purpose.

### B: Feign client (declarative HTTP client)
OpenFeign lets you define HTTP clients as annotated interfaces:
```java
@FeignClient(name = "gitlab")
interface GitLabApi {
    @GetMapping("/repository/tree")
    List<Map<String, Object>> getTree(@RequestParam String path, @RequestParam String ref);
}
```
This is clean and testable. The reason it was not used: Feign requires the `spring-cloud-openfeign` dependency, which brings in Spring Cloud. That is a significant dependency boundary to cross for a standalone service that has no other Cloud dependencies.

### C: Lazy session initialisation (no `@PostConstruct`)
The session could be built on the first call rather than at startup. The code already has a null check in `getSession()` for this reason. The `@PostConstruct` was added to make startup failures visible immediately. Both approaches work — `@PostConstruct` is strictly preferable for operator experience.

---

## Consequences

- The `GitLabSession` is built once at startup and cached. If the GitLab token is rotated, the application must restart to pick up the new token (the `RestClient` is built with the old token as a default header).
- `GitLabSession` is not thread-safe in the sense that `initSession` writes to the `session` field once and `getSession()` reads it, with no synchronisation. This is safe because `@PostConstruct` completes before the application accepts requests, so by the time `getSession()` is called the field is already populated.
