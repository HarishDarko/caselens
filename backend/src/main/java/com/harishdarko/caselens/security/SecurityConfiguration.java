package com.harishdarko.caselens.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harishdarko.caselens.demo.DemoTokenService;
import com.harishdarko.caselens.demo.DemoWorkspaceAccess;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfiguration {
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${caselens.web.origins:http://localhost:4173,http://127.0.0.1:4173}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim).filter(origin -> !origin.isBlank()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID"));
        configuration.setExposedHeaders(List.of("X-Correlation-ID", "Retry-After"));
        configuration.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    RateLimitService rateLimitService(Clock clock,
            @Value("${caselens.rate-limit.default-per-minute:30}") int defaultLimit) {
        return new RateLimitService(clock, defaultLimit, Duration.ofMinutes(1));
    }

    @Bean
    DemoRateLimitFilter demoRateLimitFilter(RateLimitService limits, ObjectMapper objectMapper,
            @Value("${caselens.rate-limit.session-per-minute:20}") int sessionLimit,
            @Value("${caselens.rate-limit.triage-per-minute:30}") int triageLimit,
            @Value("${caselens.rate-limit.mutation-per-minute:${caselens.rate-limit.default-per-minute:30}}") int mutationLimit) {
        return new DemoRateLimitFilter(limits, objectMapper, sessionLimit, triageLimit, mutationLimit);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DemoTokenService tokens, ObjectMapper objectMapper,
            DemoWorkspaceAccess workspaces, DemoRateLimitFilter rateLimitFilter)
            throws Exception {
        DemoBearerTokenFilter bearerFilter = new DemoBearerTokenFilter(tokens, objectMapper, workspaces);
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/demo/session", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/metrics/**").authenticated()
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
                .addFilterAfter(rateLimitFilter, DemoBearerTokenFilter.class)
                .build();
    }
}
