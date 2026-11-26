package com.pulseagent.security;

import com.pulseagent.config.AppProperties;
import com.pulseagent.tenant.ApiKeyService;
import com.pulseagent.tenant.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
public class SecurityConfig {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ApiKeyService keys, SessionTokenService sessions,
                                    UserService users, AppProperties props) throws Exception {
        AuthFilter authFilter = new AuthFilter(keys, sessions, users);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(props);
        WidgetCorsFilter widgetCors = new WidgetCorsFilter();

        http
            // Stateless API: CSRF is handled by SameSite=Strict cookies plus a required custom header.
            .csrf(c -> c.disable())
            .cors(c -> c.disable())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(t -> t.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                // The widget script and widget API are loaded by other sites; everything else is same-origin only.
                .addHeaderWriter((req, res) -> res.setHeader("Cross-Origin-Resource-Policy",
                        req.getRequestURI().equals("/widget.js") || req.getRequestURI().startsWith("/api/widget/") ? "cross-origin" : "same-origin")))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/login", "/api/auth/signup", "/api/auth/logout", "/api/auth/config",
                        "/api/health", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/errors", "/api/v1/webhooks/grafana").hasAnyRole("INGEST", "MEMBER")
                .requestMatchers("/api/widget/**").hasRole("WIDGET")
                .requestMatchers("/api/admin/**").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/actions/*/approve", "/api/actions/*/reject").hasRole("OWNER")
                .requestMatchers("/api/**", "/actuator/**").hasRole("MEMBER")
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"unauthorized\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"forbidden\"}");
                }))
            .addFilterBefore(widgetCors, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, WidgetCorsFilter.class)
            .addFilterAfter(authFilter, RateLimitFilter.class);
        return http.build();
    }
}
