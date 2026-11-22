package com.harishdarko.caselens.security;

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
import tools.jackson.databind.ObjectMapper;

final class DemoRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DemoRateLimitFilter.class);
    static final int MAX_CLIENT_ADDRESS_LENGTH = ClientAddresses.MAX_CLIENT_ADDRESS_LENGTH;
    private static final String TRIAGE_PATH = "/api/tickets/[^/]+/triage";
    private static final String FEEDBACK_PATH = "/api/tickets/[^/]+/feedback";
    private final RateLimitService limits;
    private final ObjectMapper objectMapper;
    private final ClientAddresses addresses;
    private final Limits perMinute;

    /** Requests per minute for each kind of POST. */
    record Limits(int session, int triage, int mutation, int ticket, int feedback) {}

    DemoRateLimitFilter(RateLimitService limits, ObjectMapper objectMapper, boolean trustGatewayClientIp, Limits perMinute) {
        this(limits, objectMapper, new ClientAddresses(trustGatewayClientIp), perMinute);
    }

    DemoRateLimitFilter(RateLimitService limits, ObjectMapper objectMapper, ClientAddresses addresses, Limits perMinute) {
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.addresses = addresses;
        this.perMinute = perMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = bucketFor(request);
        if (bucket == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            limits.acquire(bucket.key(), bucket.limit());
            chain.doFilter(request, response);
        } catch (RateLimitExceededException exceeded) {
            log.warn("security.rate_limited key={}", SensitiveDataSanitizer.text(bucket.key()));
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(exceeded.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "type", "about:blank", "title", "Too many requests", "status", 429,
                    "detail", "Try again after the retry window"));
        }
    }

    Bucket bucketFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return null;
        String uri = request.getRequestURI();
        if (uri.equals("/api/demo/session")) return new Bucket("session:" + clientAddress(request), perMinute.session());
        if (uri.matches(TRIAGE_PATH)) return new Bucket("triage:" + owner(request), perMinute.triage());
        if (uri.equals("/api/tickets")) return new Bucket("ticket:" + owner(request), perMinute.ticket());
        if (uri.matches(FEEDBACK_PATH)) return new Bucket("feedback:" + owner(request), perMinute.feedback());
        if (uri.startsWith("/api/demo/") || uri.matches("/api/operations/failures/[^/]+/retry")) {
            return new Bucket("mutation:" + owner(request), perMinute.mutation());
        }
        return null;
    }

    /** The workspace for a signed-in caller; the client address before the request is turned away as 401. */
    private String owner(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof DemoPrincipal principal) {
            return principal.workspaceId().toString();
        }
        return clientAddress(request);
    }

    String clientAddress(HttpServletRequest request) {
        return addresses.address(request);
    }

    record Bucket(String key, int limit) {}
}
