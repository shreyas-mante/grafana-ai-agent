package com.pulseagent.security;

import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.ApiKeyService;
import com.pulseagent.tenant.UserService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the caller to an {@link Actor}:
 *  - machine clients: X-API-Key or "Authorization: Bearer" (admin or ingest key)
 *  - embedded widget: X-Widget-Key, only on /api/widget/**, only from allowed origins
 *  - web UI: signed HttpOnly session cookie; role re-checked in the database on every request,
 *    and writes must carry a custom header (CSRF defence on top of SameSite=Strict)
 */
public class AuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "pa_session";
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_VALUE = "pulseagent";

    private final ApiKeyService keys;
    private final SessionTokenService sessions;
    private final UserService users;

    public AuthFilter(ApiKeyService keys, SessionTokenService sessions, UserService users) {
        this.keys = keys;
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String widgetKey = req.getHeader("X-Widget-Key");
            String key = req.getHeader("X-API-Key");
            if (key == null) {
                String auth = req.getHeader("Authorization");
                if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) key = auth.substring(7).trim();
            }

            if (widgetKey != null) {
                Optional<ApiKeyService.KeyInfo> info = keys.authenticate(widgetKey);
                String origin = req.getHeader("Origin");
                if (info.isEmpty() || !"widget".equals(info.get().scope()) || !req.getRequestURI().startsWith("/api/widget/")
                        || origin == null || !info.get().allowedOrigins().contains(origin)) {
                    deny(res);
                    return;
                }
                set(new Actor(info.get().workspaceId(), "key:" + info.get().id(), "Widget (" + origin + ")", Actor.Role.WIDGET, "widget", info.get().allowedOrigins()));
            } else if (key != null) {
                Optional<ApiKeyService.KeyInfo> info = keys.authenticate(key);
                if (info.isEmpty() || "widget".equals(info.get().scope())) {
                    deny(res);
                    return;
                }
                Actor.Role role = "admin".equals(info.get().scope()) ? Actor.Role.OWNER : Actor.Role.INGEST;
                set(new Actor(info.get().workspaceId(), "key:" + info.get().id(), "API key: " + info.get().name(), role, "api-key", List.of()));
            } else {
                String token = readCookie(req);
                Map<String, String> c = token == null ? null : sessions.verify(token);
                if (c != null) {
                    boolean unsafe = !("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()) || "OPTIONS".equals(req.getMethod()));
                    if (!unsafe || CSRF_VALUE.equals(req.getHeader(CSRF_HEADER))) {
                        String ws = c.get("ws");
                        if ("user".equals(c.get("kind"))) {
                            users.role(c.get("sub"), ws).ifPresent(r ->
                                    set(new Actor(ws, c.get("sub"), c.get("name"), Actor.Role.valueOf(r), "session", List.of())));
                        } else if ("key".equals(c.get("kind")) && keys.isActive(c.get("sub"))) {
                            set(new Actor(ws, "key:" + c.get("sub"), c.get("name"), Actor.Role.OWNER, "session", List.of()));
                        }
                    }
                }
            }
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

    private static void set(Actor actor) {
        List<SimpleGrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_" + actor.role().name()));
        if (actor.role() == Actor.Role.OWNER) auths.add(new SimpleGrantedAuthority("ROLE_MEMBER"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, auths));
    }

    private static void deny(HttpServletResponse res) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
