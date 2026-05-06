# ADR-003: DTO and Domain Model Separation

**Status:** Accepted

---

## Context

The original code used a single `Job` class for everything: the internal representation of a Kubernetes job AND the JSON body returned to API callers. Likewise, `JobCreateRequest` and `JobCreateResponse` lived in the `model/` package alongside the domain class.

This creates a coupling problem. If you ever want to:
- Add an internal field to `Job` that should not be exposed to callers
- Return a different shape to the API without changing your internal model
- Version your API response without versioning your domain type

...you cannot, because the API contract and the internal model are the same object.

---

## Decision

Two separate packages:

**`model/`** — Internal domain objects. Used by the service layer and below. Never returned directly from a controller.

```
model/
└── Job.java     id, name, status, created (OffsetDateTime), completed (OffsetDateTime)
```

**`dto/`** — Data Transfer Objects. The API wire format. Only the controller layer touches these.

```
dto/
├── JobDto.java            What callers receive when asking about a job
├── CreateJobRequest.java  What callers send when creating a job
├── CreateJobResponse.java What callers receive after creating a job
└── ErrorResponse.java     What callers receive when something goes wrong
```

### Where does the mapping happen?

The controller is responsible for mapping domain → DTO. The service returns `Job` objects; the controller converts them to `JobDto` before sending the response:

```java
// JobController
public ResponseEntity<List<JobDto>> getAllJobs() {
    return ResponseEntity.ok(
        jobService.getAllJobs().stream().map(JobDto::from).toList()
    );
}
```

`JobDto.from(Job)` is a static factory on the DTO itself. This keeps the mapping logic inside the DTO class, so the controller only calls one method and the mapping rules are easy to find and change.

### Why the service does not accept or return DTOs

`JobService.createJob(String templateName, Map<String, Object> overrides)` takes plain parameters, not `CreateJobRequest`. This means the service has no knowledge of the API layer. If the request shape changes (new field, renamed field), the controller handles the translation — the service is untouched.

---

## Alternatives Considered

### A: Single class for both domain and API (original approach)
Simple upfront. Becomes a problem the first time you need to add an internal field (like a raw `V1Job` reference for caching) that must not be serialised to the caller, or when you want to rename a field in the response without breaking internal code that uses it.

### B: Separate DTOs but use MapStruct for mapping
MapStruct generates the mapping code at compile time from an annotated interface. It is the right choice when there are many fields or complex transformations. Here `Job` and `JobDto` have the same five fields — the overhead of adding a MapStruct dependency and a mapper interface outweighs the benefit. A `static JobDto.from(Job)` factory method is sufficient.

### C: Interface-based projections (Spring Data style)
Works well when pulling data from a database — the database query can project directly into the interface. Not applicable here since data comes from Kubernetes, not a Spring Data repository.

### D: `@JsonIgnore` on internal fields of a single class
Annotating fields with `@JsonIgnore` to hide them from serialisation is a common shortcut. It contaminates the domain model with HTTP serialisation concerns. The domain class then has a dependency on Jackson annotations. Rejected.

---

## Consequences

- `Job` can gain internal fields freely without worrying about them appearing in API responses.
- The API shape (`JobDto`) can evolve (add fields, change names) without touching the domain model.
- `JobDto` is currently identical to `Job` in field names and types. This feels redundant now. The separation exists for when they diverge — and they will, because domain objects and API contracts evolve at different rates.
- The mapping in `JobDto.from(Job)` is the single place to update when the two shapes differ.
