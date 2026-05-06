# k8s-governor Documentation

This folder contains all design documentation for the k8s-governor service. Each decision that shaped the codebase — including what alternatives were considered and why they were rejected — is recorded here.

## Contents

| Document | What it covers |
|---|---|
| [architecture.md](architecture.md) | System overview, request flows, component map |
| [adr/001-package-structure.md](adr/001-package-structure.md) | Why packages are split by domain, not by layer |
| [adr/002-config-design.md](adr/002-config-design.md) | `@ConfigurationProperties`, `@Getter @Setter` vs `@Data`, timeout externalisation |
| [adr/003-dto-model-separation.md](adr/003-dto-model-separation.md) | Why `model/Job` and `dto/JobDto` are separate classes |
| [adr/004-exception-handling.md](adr/004-exception-handling.md) | `@ControllerAdvice`, `ErrorResponse` shape, `ResponseStatusException` propagation |
| [adr/005-security-gatekeeper.md](adr/005-security-gatekeeper.md) | API-key filter design, constant-time comparison, why not Spring Security |
| [adr/006-gitlab-client-design.md](adr/006-gitlab-client-design.md) | Session initialisation, `RestClient`, named URI variables, `GitLabSession` as a record |
| [adr/007-template-rendering.md](adr/007-template-rendering.md) | Regex substitution over Helm CLI, layered values, path traversal guard, job name suffix |
| [adr/008-kubernetes-integration.md](adr/008-kubernetes-integration.md) | `Config.defaultClient()`, `BatchV1Api`, `OffsetDateTime`, Foreground deletion |

## How to read the ADRs

Each ADR follows the same structure:

- **Status** — current state of the decision
- **Context** — the problem that forced a decision
- **Decision** — what was chosen and the key reasoning
- **Alternatives considered** — what else was evaluated and why it was not chosen
- **Consequences** — what this decision means going forward (trade-offs accepted)
