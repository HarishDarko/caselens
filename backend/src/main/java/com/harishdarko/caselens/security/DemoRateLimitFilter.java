package com.harishdarko.caselens.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.common.SensitiveDataSanitizer;
import com.harishdarko.caselens.demo.DemoPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class DemoRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DemoRateLimitFilter.class);
    private final RateLimitService limits;
    private final ObjectMapper objectMapper;
    private final int sessionLimit;
    private final int triageLimit;
    private final int mutationLimit;

    DemoRateLimitFilter(RateLimitService limits, ObjectMapper objectMapper, int sessionLimit, int triageLimit, int mutationLimit) {
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.sessionLimit = sessionLimit;
        this.triageLimit = triageLimit;
        this.mutationLimit = mutationLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = keyFor(request);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            limits.acquire(key, limitFor(request));
            chain.doFilter(request, response);
        } catch (RateLimitExceededException exceeded) {
            log.warn("security.rate_limited key={}", SensitiveDataSanitizer.text(key));
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(exceeded.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "type", "about:blank", "title", "Too many requests", "status", 429,
                    "detail", "Try again after the retry window"));
        }
    }

    private String keyFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return null;
        if (request.getRequestURI().equals("/api/demo/session")) return "session:" + clientAddress(request);
        if (request.getRequestURI().matches("/api/tickets/[^/]+/triage")) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
                return "triage:" + principal.workspaceId();
            }
            return "triage:" + clientAddress(request);
        }
        if (request.getRequestURI().startsWith("/api/demo/")
                || request.getRequestURI().matches("/api/operations/failures/[^/]+/retry")) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
                return "mutation:" + principal.workspaceId();
            }
            return "mutation:" + clientAddress(request);
        }
        return null;
    }

    private String clientAddress(HttpServletRequest request) {
        String gatewayAddress = request.getHeader("X-CaseLens-Client-Ip");
        if (gatewayAddress != null && !gatewayAddress.isBlank()) return gatewayAddress.trim();
        return request.getRemoteAddr();
    }

    private int limitFor(HttpServletRequest request) {
        if (request.getRequestURI().equals("/api/demo/session")) return sessionLimit;
        if (request.getRequestURI().matches("/api/tickets/[^/]+/triage")) return triageLimit;
        return mutationLimit;
    }
}
