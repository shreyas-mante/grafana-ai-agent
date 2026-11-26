package com.pulseagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates a request by API key (X-API-Key or "Authorization: Bearer") for machine clients,
 * or by the signed session cookie for the web UI. Cookie-authenticated writes must also carry a
 * custom header, which cross-site forms cannot send (CSRF defence on top of SameSite=Strict).
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "pa_session";
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_VALUE = "pulseagent";

    private final ApiKeyService keys;
    private final SessionTokenService sessions;

    public AuthFilter(ApiKeyService keys, SessionTokenService sessions) {
        this.keys = keys;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        if (key == null) {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) key = auth.substring(7).trim();
        }

        if (key != null) {
            Optional<ApiKeyService.Role> role = keys.authenticate(key);
            if (role.isEmpty()) {
                deny(res);
                return;
            }
            setAuth("api-key", role.get().name());
        } else {
            String token = readCookie(req);
            if (token != null) {
                String subject = sessions.verify(token);
                boolean unsafe = !("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()) || "OPTIONS".equals(req.getMethod()));
                if (subject != null && (!unsafe || CSRF_VALUE.equals(req.getHeader(CSRF_HEADER)))) {
                    setAuth(subject, ApiKeyService.Role.ADMIN.name());
                }
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static String readCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    private static void setAuth(String principal, String role) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void deny(HttpServletResponse res) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
