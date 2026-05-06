# ADR-007: Template Rendering

**Status:** Accepted

---

## Context

Job definitions are stored as YAML files in a GitLab Helm repository. They contain placeholder values that need to be substituted before the YAML can be deployed to Kubernetes — for example, environment-specific image tags, resource limits, or schedule expressions.

The templates use a Helm-like syntax: `{{ .Values.someKey }}`. Before deploying, these placeholders must be replaced with actual values sourced from `values.yaml` files in the same repository, with optional overrides per environment and region.

Decisions needed:
- How to perform the substitution (engine choice)
- How to structure the layered values files
- How to guarantee unique job names on each run
- Whether to involve Helm at all

---

## Decision

### 1. Regex-based substitution — no Helm binary

The placeholder pattern `{{ .Values.someKey }}` is processed by a Java regex, not by the Helm binary. The pattern:

```java
Pattern.compile("\\{\\{\\s*\\.Values\\.([\\w\\.]+)\\s*}}")
```

This matches `{{ .Values.x }}`, `{{ .Values.x.y.z }}`, and handles optional whitespace inside the braces.

Nested key paths (e.g., `{{ .Values.job.loader.image }}`) are resolved by splitting on `.` and walking the merged values map:

```java
private Object resolveValue(String keyPath, Map<String, Object> values) {
    String[] parts = keyPath.split("\\.");
    Object current = values;
    for (String part : parts) {
        if (!(current instanceof Map<?, ?> map)) return null;
        current = map.get(part);
        if (current == null) return null;
    }
    return current;
}
```

If a placeholder has no matching value in the merged map, it is replaced with an empty string. This is a deliberate choice — a missing value produces a silent gap rather than a deployment failure. The trade-off is discussed in Consequences.

### 2. Layered values files

Values are merged from three sources in order. Each layer overrides keys from the previous:

```
values.yaml                      ← base defaults (required, fetched from GitLab)
values-{env}.yaml                ← environment overrides (optional)
values-{env}-{region}.yaml       ← region overrides (optional)
request.overrides                ← per-call overrides from the API request body
```

This mirrors Helm's own values override model (`--values`, `--set`). The structure allows a single template to be deployed across many environment/region combinations without duplicating the template file.

`values.yaml` is required — `fetchYamlFile` is called with `optional=false`, which throws a `404` if the file is missing. The layered files are optional, fetched with `optional=true`, which logs and returns an empty map if not found.

### 3. Auto-generated job name suffix

Kubernetes jobs must have unique names within a namespace. If the same job is submitted twice, the second will fail with a name conflict.

The values files contain a base name (e.g., `loaderholiday`). A suffix is appended automatically at render time:

```java
String finalName = base + "-" + generateJobNameSuffix();
// e.g. loaderholiday-20260506102345-a1b2c3d4
```

The suffix format is `yyyyMMddHHmmss-XXXXXXXX` (UTC timestamp + 8 hex chars from a UUID). This guarantees uniqueness without requiring a database or counter service.

Detection is pattern-based: `{{ .Values.job.{type}.name }}` placeholders trigger the suffix injection. Only the first occurrence of each job type is processed (the `processed` set guards against double-injection for templates that reference the same name twice).

The suffix is injected into the `allValues` map before placeholder substitution runs, so the rendered name flows through the normal substitution path.

### 4. YAML → `V1Job` conversion via SnakeYAML + Gson

After rendering, the template string is a valid YAML document. Converting it to a `V1Job` object (the Kubernetes SDK type) is a two-step process:

```java
// Step 1: YAML string → generic Map
Yaml yamlParser = new Yaml(new SafeConstructor(new LoaderOptions()));
Map<String, Object> yamlMap = yamlParser.load(renderedTemplate);

// Step 2: generic Map → V1Job
Gson gson = new JSON().getGson();
V1Job v1Job = gson.fromJson(gson.toJson(yamlMap), V1Job.class);
```

`SafeConstructor` is used for SnakeYAML parsing. SnakeYAML by default allows YAML to instantiate arbitrary Java classes via `!!java.lang.Runtime` style tags. `SafeConstructor` restricts parsing to standard Java types only, preventing YAML injection attacks.

`JSON().getGson()` uses the Kubernetes SDK's pre-configured Gson instance, which knows how to deserialise Kubernetes-specific types (`V1Job`, `V1ObjectMeta`, etc.) including their date format handling.

---

## Alternatives Considered

### A: Run Helm CLI directly
The most obvious approach: shell out to `helm template` and let Helm handle rendering.

Rejected because:
- Requires Helm to be installed in the runtime container
- Introduces a process execution dependency that is fragile and hard to test
- The templates use only `{{ .Values.xxx }}` substitution — no Helm-specific functions (`toYaml`, `include`, `required`, `tpl`, etc.). Full Helm is overkill for simple value substitution.

If templates ever need Helm-specific functions, using the Helm CLI would become necessary.

### B: Mustache
Mustache is a logic-less template engine available as a Java library (`com.github.spullara.mustache.java`). It supports `{{variable}}` syntax natively.

The issue: Mustache uses `{{variable}}` (no dot prefix), while the existing templates use `{{ .Values.xxx }}`. Adopting Mustache would require either modifying all templates or writing an adapter to make Mustache understand the `.Values.` prefix. A regex substitution is simpler than either.

### C: Freemarker / Thymeleaf
Full-featured template engines. Both support arbitrary control flow (conditionals, loops), not just substitution. This is more power than needed and introduces a learning curve for anyone editing templates.

### D: Direct `String.replace()` per key
The simplest possible approach: iterate the values map and call `string.replace("{{ .Values.key }}", value)` for each entry.

Problems:
- Does not handle whitespace variants (`{{.Values.key}}` vs `{{ .Values.key }}`)
- Does not handle nested keys (`{{ .Values.a.b.c }}`)
- Iterating the entire flat map for nested keys requires flattening the map first

The regex approach handles all of these cleanly.

---

## Consequences

- **Silent missing values**: If a placeholder has no corresponding entry in the merged values map, it is replaced with an empty string and no error is raised. This can produce invalid YAML (e.g., `image: ` with no value). If this becomes a problem, the renderer should be changed to throw on unresolved placeholders.
- **Template syntax is locked**: The renderer only understands `{{ .Values.xxx }}`. Templates cannot use Helm functions, conditionals, or loops. This is acceptable given the current template complexity.
- **No template validation**: The rendered template is parsed by SnakeYAML after substitution. If substitution produces invalid YAML, the error surfaces at the YAML parse step with a potentially unhelpful error message.
