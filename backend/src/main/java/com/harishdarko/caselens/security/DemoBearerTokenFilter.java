package com.harishdarko.caselens.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.demo.DemoPrincipal;
import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.demo.DemoWorkspaceAccess;
import com.harishdarko.caselens.demo.InvalidDemoTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class DemoBearerTokenFilter extends OncePerRequestFilter {
    private final DemoTokenService tokens;
    private final ObjectMapper objectMapper;
    private final DemoWorkspaceAccess workspaces;

    DemoBearerTokenFilter(DemoTokenService tokens, ObjectMapper objectMapper, DemoWorkspaceAccess workspaces) {
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.workspaces = workspaces;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || request.getRequestURI().equals("/api/demo/session");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                DemoPrincipal principal = tokens.verify(authorization.substring(7));
                workspaces.requireActive(principal.workspaceId());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, List.of()));
            } catch (InvalidDemoTokenException exception) {
                writeUnauthorized(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorBody(
                "about:blank", "Unauthorized", 401, "Invalid or expired demo session"));
    }

    private record ErrorBody(String type, String title, int status, String detail) {}
}
