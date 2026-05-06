# ADR-005: API Key Security — Gatekeeper Filter

**Status:** Accepted

---

## Context

The service exposes endpoints that create and delete Kubernetes jobs. These are destructive/mutating operations that must be protected. Read-only endpoints (list jobs, get job, list templates) do not require authentication — they expose no sensitive data and trigger no side effects.

The authentication mechanism needed to be simple, stateless, and deployable without an identity provider.

---

## Decision

### 1. Servlet filter (`OncePerRequestFilter`)

`Gatekeeper` extends Spring's `OncePerRequestFilter` and is registered as a `@Component`. Spring Boot auto-registers it in the filter chain without any additional configuration.

`OncePerRequestFilter` guarantees the filter runs exactly once per request, even in forwarded/included request scenarios.

### 2. Selective filtering via `shouldNotFilter`

Rather than filtering all requests and then checking whether authentication is needed, `shouldNotFilter` declares upfront which requests are excluded:

```java
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    boolean isCreate = "POST".equalsIgnoreCase(method) && "/jobs".equals(path);
    boolean isDelete = "DELETE".equalsIgnoreCase(method) && path.startsWith("/jobs/");
    return !(isCreate || isDelete);
}
```

Only `POST /jobs` and `DELETE /jobs/*` are protected. All GET requests pass through without authentication.

`request.getServletPath()` is used (not `getRequestURI()`). The distinction matters: `getRequestURI()` returns the full URI including the context path (`/governor/jobs`), so the code would need to manually strip `/governor`. `getServletPath()` returns the path relative to the context path (`/jobs`) — the servlet container strips the context path automatically.

The original code manually stripped the context path:
```java
String path = requestUri.startsWith(contextPath)
    ? requestUri.substring(contextPath.length())
    : requestUri;
```
This was fragile — if the context path changed, this code would silently break. `getServletPath()` is immune to context path changes.

### 3. Constant-time comparison via `MessageDigest.isEqual`

```java
return MessageDigest.isEqual(
    expected.getBytes(StandardCharsets.UTF_8),
    provided.getBytes(StandardCharsets.UTF_8)
);
```

**Why not `expected.equals(provided)`?**

String `equals()` short-circuits — it returns `false` as soon as it finds the first differing character. This means it takes slightly less time when the strings differ early. An attacker can measure these timing differences across many requests (a *timing attack*) to deduce the correct API key one character at a time.

`MessageDigest.isEqual` performs the comparison in constant time regardless of where the strings differ. This is standard practice for any secret comparison.

---

## Alternatives Considered

### A: Spring Security
Spring Security is the canonical approach for authentication in Spring Boot. It provides `HttpSecurity` configuration, `SecurityFilterChain`, and built-in support for API keys, JWT, OAuth2, and more.

It was not used here for two reasons:
1. **Dependency weight**: Spring Security pulls in a large dependency and requires careful configuration. Getting it wrong (e.g., accidentally applying CSRF protection to an API, or blocking actuator endpoints) causes subtle failures. For a single authentication rule, the overhead is not justified.
2. **Explicitness**: The `Gatekeeper` filter is ~60 lines and does exactly one thing. Someone new to the codebase can read it in two minutes and understand the full security model. Spring Security would require understanding the filter chain ordering, `SecurityFilterChain` bean configuration, and how `shouldNotFilter` maps to `requestMatchers`.

If authentication requirements grow (multiple API keys, role-based access, service tokens), migrating to Spring Security is the right call.

### B: Controller-level annotation + `HandlerInterceptor`
A custom annotation like `@RequiresApiKey` on individual controller methods, enforced by a `HandlerInterceptor`. This is more granular than a filter and works at the Spring MVC layer rather than the servlet layer.

The downside: the protection is opt-in per method. Adding a new mutating endpoint means remembering to add the annotation. The filter approach is opt-out — all requests go through it, and `shouldNotFilter` explicitly lists what is excluded. An omission with a filter fails safe (the new endpoint gets protected by default); an omission with annotations fails open (the new endpoint is unprotected).

### C: API Gateway authentication (no in-app auth)
Push authentication to an API gateway or sidecar proxy that validates the key before the request reaches this service. This is architecturally clean — the service has zero authentication code and trusts that the gateway enforces it.

This requires infrastructure that may not exist. In-app authentication works without any surrounding infrastructure and provides defence-in-depth if the gateway is misconfigured.

---

## Consequences

- If a new mutating endpoint is added (e.g., `PATCH /jobs/{name}`), `shouldNotFilter` must be updated to protect it. This is a manual step with no compiler enforcement.
- The API key is compared as plaintext. If the key is stored in a secret manager and rotated, the application must restart (or the config must be reloaded) to pick up the new value.
- GET endpoints are completely open. If read access to job status should also be restricted in the future, the `shouldNotFilter` logic needs expanding.
