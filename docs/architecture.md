# Architecture Overview

## Purpose

k8s-governor is a lightweight HTTP API that manages Kubernetes batch jobs. Its core job is to bridge two external systems: a **GitLab Helm repository** (which holds job templates) and a **Kubernetes cluster** (which runs the jobs).

Callers never interact with GitLab or Kubernetes directly. They call this service, which fetches templates, renders them with environment-specific values, and deploys or queries jobs on their behalf.

---

## System Context

```
┌────────────┐        HTTP (JSON)        ┌─────────────────────┐
│  Caller    │ ───────────────────────►  │   k8s-governor      │
│ (internal) │                           │   :9663/governor/   │
└────────────┘                           └──────────┬──────────┘
                                                    │
                              ┌─────────────────────┼─────────────────────┐
                              │                     │                     │
                              ▼                     ▼                     │
                  ┌───────────────────┐  ┌─────────────────────┐         │
                  │  GitLab Repo      │  │  Kubernetes Cluster  │         │
                  │  (Helm templates  │  │  (BatchV1 API)       │         │
                  │   + values files) │  │                      │         │
                  └───────────────────┘  └─────────────────────┘         │
```

---

## Package Map

```
com.k8sgovernor/
│
├── config/          Spring configuration beans
│   ├── AppConfig    Binds all application.yaml config into typed POJOs
│   └── K8sConfig    Creates the Kubernetes ApiClient and BatchV1Api beans
│
├── controller/      HTTP layer — thin, no business logic
│   └── JobController
│
├── service/         Business logic — orchestrates GitLab + Kubernetes operations
│   └── JobService
│
├── dto/             API wire format (request/response shapes visible to callers)
│   ├── CreateJobRequest
│   ├── CreateJobResponse
│   ├── JobDto
│   └── ErrorResponse
│
├── model/           Internal domain objects (not exposed directly via API)
│   └── Job
│
├── gitlab/          GitLab integration — session management, file fetching, rendering
│   ├── GitLabClient
│   └── GitLabSession
│
├── kubernetes/      Kubernetes integration — maps K8s types to domain types
│   └── JobMapper
│
├── security/        Inbound authentication filter
│   └── Gatekeeper
│
└── exception/       Centralised error response formatting
    └── GlobalExceptionHandler
```

---

## Request Flows

### GET /jobs (list all jobs)

```
JobController.getAllJobs()
    └── JobService.getAllJobs()
            └── BatchV1Api.listNamespacedJob(namespace)   [Kubernetes]
            └── JobMapper.toJob(v1Job)                    [for each result]
    └── JobDto.from(job)                                  [for each result, in controller]
```

### GET /jobs/{name} (get one job)

```
JobController.getJobByName(name)
    └── JobService.getJobByName(name)
            └── BatchV1Api.readNamespacedJob(name, namespace)   [Kubernetes]
            └── JobMapper.toJob(v1Job)
    └── JobDto.from(job)
```

### GET /jobs/templates (list available templates)

```
JobController.getJobTemplateNames()
    └── JobService.getJobTemplateNames()
            └── GitLabClient.getSession()
            └── RestClient GET /repository/tree?path=jobs&ref=branch   [GitLab]
```

### POST /jobs (create a job)  ← most complex flow

```
Gatekeeper.doFilterInternal()               [API key validated here]
    └── JobController.createJob(request)
            └── JobService.createJob(templateName, overrides)
                    ├── GitLabClient.fetchTemplate(templateName)
                    │       └── RestClient GET /repository/files/{path}/raw   [GitLab]
                    │
                    ├── GitLabClient.fetchAndMergeValues(env, region)
                    │       ├── fetch values.yaml                             [GitLab]
                    │       ├── fetch values-{env}.yaml          (optional)   [GitLab]
                    │       └── fetch values-{env}-{region}.yaml (optional)   [GitLab]
                    │
                    ├── GitLabClient.renderTemplate(raw, mergedValues, overrides)
                    │       ├── injectGeneratedJobNames()   [appends timestamp+uuid suffix]
                    │       └── replace {{ .Values.xxx }} placeholders
                    │
                    ├── SnakeYAML: parse rendered YAML string → Map
                    ├── Gson: Map → V1Job object
                    └── BatchV1Api.createNamespacedJob(namespace, v1Job)      [Kubernetes]
```

### DELETE /jobs/{name}

```
Gatekeeper.doFilterInternal()               [API key validated here]
    └── JobController.deleteJobByName(name)
            └── JobService.deleteJobByName(name)
                    └── BatchV1Api.deleteNamespacedJob(name, namespace, "Foreground")
```

---

## Values Layering

When creating a job, values are merged in order — each layer overrides the previous:

```
values.yaml                     ← base defaults (required)
    ▲
values-{env}.yaml               ← environment overrides, e.g. values-uat.yaml (optional)
    ▲
values-{env}-{region}.yaml      ← region overrides, e.g. values-uat-london.yaml (optional)
    ▲
request.overrides               ← per-call overrides from the API request body (optional)
```

All files are fetched from the same GitLab Helm repository configured in `application.yaml`.

---

## Environment Variables Required

| Variable | Purpose |
|---|---|
| `GOVERNOR_API_KEY` | API key callers must supply on mutating requests |
| `GOVERNOR_API_KEY_HEADER` | Header name to read the key from (default: `X-API-Key`) |
| `APP_ENV` | Environment name used in values file layering (e.g. `uat`, `prod`) |
| `APP_REGION` | Region name used in values file layering (e.g. `london`, `tokyo`) |
| `K8S_NAMESPACE` | Kubernetes namespace all job operations are scoped to |
| `K8S_SERVICE_ACCOUNT` | Service account logged at startup for audit purposes |
| `HELM_REPO_URL` | Full URL of the GitLab Helm repository |
| `HELM_BRANCH` | Branch to read templates from (default: `bau-redhill`) |
| `HELM_DIRECTORY` | Directory within the repo where job templates live (default: `jobs`) |
| `GITLAB_ACCESS_TOKEN` | GitLab personal/project access token for API requests |
