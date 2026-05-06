# ADR-001: Package Structure

**Status:** Accepted

---

## Context

The original code lived under `Kubernetes.Governor` — an uppercase package name, which is invalid in Java (packages must be lowercase). Beyond the naming issue, all helper classes were dumped into a single `util` package regardless of what they actually did, which made the codebase hard to navigate.

Two structural questions needed answering:

1. How should packages be named?
2. Should packages be organised by **layer** (controller, service, repository) or by **domain** (gitlab, kubernetes)?

---

## Decision

Packages are named `com.k8sgovernor.*` and organised **primarily by domain**, with a layer-based split at the top level for the main application concerns:

```
config/       Spring wiring — not a business domain
controller/   HTTP layer
service/      Business logic
model/        Internal domain objects
dto/          API wire format
security/     Inbound auth
exception/    Error handling

gitlab/       Everything relating to the GitLab integration
kubernetes/   Everything relating to the Kubernetes integration
```

The key rule: if a class exists to talk to an external system, it lives in that system's package (`gitlab/`, `kubernetes/`), not in a generic `util/` bucket.

---

## Alternatives Considered

### A: Pure layer-based packaging
```
controller/
service/
repository/
model/
util/
```
This is the most common Spring Boot convention. The problem is that `util/` becomes a catch-all that grows indefinitely. `GitLabUtils` and `KubernetesUtils` have nothing in common — they just both happened to not fit a named layer. This obscures intent.

### B: Feature-based packaging (package-by-feature)
```
jobs/
    JobController.java
    JobService.java
    Job.java
    ...
```
Works well for large apps with multiple independent features. For a service with a single resource (`jobs`) it adds nesting without clarity — everything ends up in `jobs/` anyway.

### C: Flat packaging (everything in the root)
Fine for very small prototypes. Not appropriate once the class count exceeds ~10.

---

## Consequences

- **`util/` is gone permanently.** Any new helper class must be placed in the package of the system it serves, or promoted to a named domain package.
- `GitLabClient` and `GitLabSession` living together in `gitlab/` makes it obvious they are coupled. Moving one without the other would be a conscious decision, not an accident.
- `JobMapper` in `kubernetes/` signals that it is a Kubernetes concern — it translates the K8s SDK's `V1Job` type into our domain type. If the K8s SDK were swapped out, this is the only file that changes.
- The `dto/` and `model/` split is intentional and described in detail in ADR-003.
