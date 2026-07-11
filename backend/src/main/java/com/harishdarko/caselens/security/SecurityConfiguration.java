package com.harishdarko.caselens.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.demo.DemoWorkspaceAccess;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DemoTokenService tokens, ObjectMapper objectMapper,
            DemoWorkspaceAccess workspaces)
            throws Exception {
        DemoBearerTokenFilter bearerFilter = new DemoBearerTokenFilter(tokens, objectMapper, workspaces);
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/demo/session", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(), Map.of(
                            "type", "about:blank", "title", "Unauthorized", "status", 401,
                            "detail", "A valid demo session is required"));
                }))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
