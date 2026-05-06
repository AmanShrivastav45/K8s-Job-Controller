# ADR-004: Exception Handling

**Status:** Accepted

---

## Context

Error handling in the original code was inconsistent:
- Errors were thrown as `ResponseStatusException` inline across `JobService` and `GitLabUtils`
- No consistent JSON response shape for errors — callers received Spring's default `{"timestamp":...,"status":...,"error":...,"path":...}` format, which changes between Spring Boot versions
- `ApiException` from the Kubernetes client was caught inside `createJob` by a broad `catch (Exception e)` that mapped everything to `422 Unprocessable Entity`, including infrastructure failures like the K8s API being unreachable

---

## Decision

### 1. `@RestControllerAdvice` with `GlobalExceptionHandler`

A single class annotated `@RestControllerAdvice` intercepts all unhandled exceptions thrown from any controller. This is the standard Spring MVC mechanism for centralised exception handling.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) { ... }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) { ... }
}
```

The service layer continues to throw `ResponseStatusException`. The handler intercepts it and formats it into `ErrorResponse`.

### 2. `ErrorResponse` record

Every error response now has the same structure:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Job my-job-name not found",
  "timestamp": "2026-05-06T10:23:45Z"
}
```

`ErrorResponse` is a Java record:
```java
public record ErrorResponse(int status, String error, String message, OffsetDateTime timestamp) {
    public static ErrorResponse of(int status, String error, String message) { ... }
}
```

Records are used here because `ErrorResponse` is purely a data carrier — it has no behaviour, is never mutated, and benefits from the compact syntax.

### 3. `ApiException` separated from generic `Exception` in `createJob`

The original `createJob` catch block:
```java
} catch (Exception e) {
    throw new ResponseStatusException(422, "Failed to parse or create job")
}
```

This was wrong: a `422 Unprocessable Entity` means the request was valid but couldn't be processed due to its content. If the Kubernetes API is down (`ApiException`), the correct status is `502 Bad Gateway` — a server-side infrastructure failure, not a client error.

Fixed by splitting the catch:
```java
} catch (ApiException e) {
    // Kubernetes infra failure → 502
    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ...);
} catch (Exception e) {
    // Template parse/render failure → 422
    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ...);
}
```

---

## Alternatives Considered

### A: `@ExceptionHandler` per controller
Each controller defines its own `@ExceptionHandler` methods. Works but duplicates the error-formatting logic across every controller. If you want to change the error shape, you change every controller.

### B: Custom exception class hierarchy
Define `JobNotFoundException extends RuntimeException`, `GitLabUnavailableException`, etc. and map each to a status code in the handler. This is the cleanest architecture for larger applications because the service layer throws business exceptions (no HTTP knowledge), and only the handler maps them to HTTP.

For this service the trade-off is verbosity: each exception needs its own class, its own handler method, and its own test. Given there are only a handful of error cases and the service is small, `ResponseStatusException` with an explicit status code is sufficient and keeps the class count low.

If the service grows significantly, migrating to custom exceptions is the right next step.

### C: RFC 7807 `ProblemDetail`
Spring Boot 3.x has built-in support for `ProblemDetail`, which is a standardised error format (IETF RFC 7807). It produces:
```json
{ "type": "...", "title": "Not Found", "status": 404, "detail": "...", "instance": "..." }
```
This is a reasonable choice for public-facing APIs where clients need to parse errors programmatically. For an internal API the custom `ErrorResponse` shape is simpler and gives full control over the field names without learning the RFC constraints.

---

## Consequences

- All errors, regardless of where they are thrown in the service or client layers, will produce `ErrorResponse` JSON. Callers can rely on this shape.
- Adding a new exception type or changing the error format requires changing only `GlobalExceptionHandler` — no controller changes needed.
- `ResponseStatusException.getReason()` can return `null` (it is optional). The handler guards against this by falling back to `ex.getMessage()`.
- The `handleUnexpected` catch-all logs the full stack trace. Any exception that reaches it is a programming error — it should never be silent.
