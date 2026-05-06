package com.k8sgovernor.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.k8sgovernor.config.AppConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces API-key authentication on mutating job endpoints (POST, DELETE).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Gatekeeper extends OncePerRequestFilter {

    private final AppConfig appConfig;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getServletPath() is already context-path-stripped by the servlet container
        String path = request.getServletPath();
        String method = request.getMethod();

        boolean isCreate = "POST".equalsIgnoreCase(method) && "/jobs".equals(path);
        boolean isDelete = "DELETE".equalsIgnoreCase(method) && path.startsWith("/jobs/");

        return !(isCreate || isDelete);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String expectedApiKey = appConfig.getSecurity().getApiKey();
        String headerName = appConfig.getSecurity().getApiKeyHeader();

        if (!StringUtils.hasText(headerName)) {
            headerName = "X-API-Key";
        }

        if (!StringUtils.hasText(expectedApiKey)) {
            log.error("Protected endpoint reached but config.security.api-key is not set");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "API key not configured");
            return;
        }

        String providedApiKey = request.getHeader(headerName);

        if (!isApiKeyValid(expectedApiKey, providedApiKey)) {
            log.warn("Unauthorized {} {}: missing or invalid header '{}'",
                    request.getMethod(), request.getRequestURI(), headerName);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isApiKeyValid(String expected, String provided) {
        if (!StringUtils.hasText(provided)) {
            return false;
        }
        // Constant-time comparison prevents timing attacks
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }
}
