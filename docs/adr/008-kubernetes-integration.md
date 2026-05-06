# ADR-008: Kubernetes Integration

**Status:** Accepted

---

## Context

The application needs to list, inspect, create, and delete Kubernetes batch jobs. This requires an authenticated connection to the Kubernetes API server and a way to translate between Kubernetes SDK types and the application's own domain model.

---

## Decision

### 1. Official Kubernetes Java client (`io.kubernetes:client-java`)

The `client-java` library is the official Kubernetes client for Java, maintained by the Kubernetes project. It generates SDK classes directly from the Kubernetes OpenAPI spec, meaning every Kubernetes type (`V1Job`, `V1ObjectMeta`, `V1JobStatus`, etc.) is available as a typed Java class.

The alternative (Fabric8) is discussed below.

### 2. `Config.defaultClient()` for connection setup

```java
ApiClient client = Config.defaultClient();
```

`Config.defaultClient()` uses Kubernetes' standard configuration discovery order:

1. If running **inside a pod**: uses the in-cluster service account token and CA cert mounted at `/var/run/secrets/kubernetes.io/serviceaccount/`
2. If running **outside a cluster** (local development): falls back to `~/.kube/config`

This single line handles both production (in-cluster) and local development (kubeconfig) without any conditional logic or environment checks. No `KUBECONFIG` path needs to be configured.

`io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client)` registers the client as the global default, so `new BatchV1Api()` picks it up automatically.

### 3. `BatchV1Api` for job operations

Kubernetes job operations are in the `batch/v1` API group. `BatchV1Api` is the generated client for this group and provides typed methods:

- `listNamespacedJob(namespace, ...)` — list all jobs
- `readNamespacedJob(name, namespace, ...)` — get one job
- `createNamespacedJob(namespace, v1Job, ...)` — create a job
- `deleteNamespacedJob(name, namespace, ..., "Foreground", ...)` — delete a job

`BatchV1Api` is registered as a Spring bean in `K8sConfig`, injected by `@RequiredArgsConstructor` into `JobService`.

### 4. Configurable timeouts

```java
client.setConnectTimeout(k8s.getConnectTimeoutMs());   // default: 10s
client.setReadTimeout(k8s.getReadTimeoutMs());         // default: 30s
client.setWriteTimeout(k8s.getWriteTimeoutMs());       // default: 30s
```

Timeouts are read from `AppConfig.Kubernetes` rather than hardcoded. See ADR-002 for the rationale. The defaults (10s connect, 30s read/write) are reasonable starting values for most cluster environments.

### 5. `JobMapper.toJob()` as the only conversion point

`V1Job` is a Kubernetes SDK type with dozens of fields and nested objects. The application only needs five: id, name, status, creation time, completion time. `JobMapper.toJob(V1Job)` performs this translation.

This is the single place where the application domain model (`Job`) is coupled to the Kubernetes SDK. If the SDK's type names or field accessors change between versions, only `JobMapper` needs updating.

### 6. `OffsetDateTime` for timestamps

```java
OffsetDateTime created = (meta != null && meta.getCreationTimestamp() != null)
    ? meta.getCreationTimestamp()
    : null;
```

`V1ObjectMeta.getCreationTimestamp()` returns `OffsetDateTime` (the Kubernetes SDK uses `OffsetDateTime` throughout). The value is stored as-is in the `Job` domain model — no conversion.

The original code called `.toLocalDateTime()` on the `OffsetDateTime`. `toLocalDateTime()` silently discards the UTC offset. If the JVM's default timezone is not UTC, the timestamp would be offset by the timezone difference. Using `OffsetDateTime` directly preserves the full timestamp including its UTC offset, which Kubernetes always sets to `+00:00`.

Jackson serialises `OffsetDateTime` to ISO-8601 format (`2026-05-06T10:23:45Z`) when `spring.jackson.serialization.write-dates-as-timestamps=false` is set in `application.yaml`. This is human-readable and unambiguous.

### 7. `Foreground` deletion propagation

```java
batchV1Api.deleteNamespacedJob(name, namespace, null, null, null, null, "Foreground", null);
```

The `propagationPolicy` parameter is set to `"Foreground"`. Kubernetes has three deletion propagation options:

- **`Foreground`**: The job enters a deletion state. The API call blocks until the job's pods have terminated, then the job object is deleted. The caller gets a clean, synchronous completion signal.
- **`Background`**: The job object is deleted immediately. Kubernetes garbage-collects the pods asynchronously. The caller gets a response before the pods are gone.
- **`Orphan`**: The job object is deleted but its pods are left running as orphans.

`Foreground` is chosen because it is the safest semantics for a governance service: after a `DELETE` call returns successfully, the job and its pods are genuinely gone. `Background` deletion creates a window where the job appears deleted but its pods are still running, which could confuse callers that check job existence after deletion.

---

## Alternatives Considered

### A: Fabric8 Kubernetes Client
Fabric8 (`io.fabric8:kubernetes-client`) is a popular alternative Java client for Kubernetes. It has a more fluent DSL:

```java
// Fabric8 style
client.batch().v1().jobs().inNamespace(namespace).list();
```

vs the official client:

```java
batchV1Api.listNamespacedJob(namespace, null, null, null, ...);
```

Fabric8's API is more ergonomic. The reasons for choosing the official client:
- The official client is directly generated from the Kubernetes OpenAPI spec, so it is always current with the Kubernetes API version
- No dependency on a third-party project (Fabric8 is Red Hat maintained, not CNCF/Kubernetes)
- The `Gson`-based `Map → V1Job` conversion in `createJob` uses `new JSON().getGson()`, which is the official client's own Gson instance. This would not be available with Fabric8.

### B: Direct Kubernetes REST API calls via `RestClient`
Skip the SDK entirely and call the Kubernetes API directly using `RestClient`. This removes the `client-java` dependency entirely.

The problem: Kubernetes authentication (service account tokens, client certificates), request signing, and type deserialisation are all handled by the SDK. Replicating this would be significant work with high risk of getting certificate handling wrong.

### C: `kubectl` CLI via process execution
Shell out to `kubectl` commands. Simple but:
- Requires `kubectl` in the runtime container
- No structured output — stdout parsing is fragile
- Error handling via exit codes is primitive compared to `ApiException`

---

## Consequences

- The `null` parameters in `BatchV1Api` calls (`listNamespacedJob(namespace, null, null, ...)`) are optional parameters that the SDK requires positionally. This is a known ergonomic issue with the official Java client — newer SDK versions improve on this.
- `JobMapper` has no dependency on `AppConfig` — the namespace is read directly in `JobService`. The mapper is purely a type conversion concern.
- If Kubernetes job operations need pagination (listing large numbers of jobs), `listNamespacedJob` supports a `limit` and `continue` token. The current implementation does not paginate — it fetches all jobs in one call.
