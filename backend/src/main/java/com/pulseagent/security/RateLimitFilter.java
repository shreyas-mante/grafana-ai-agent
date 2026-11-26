package com.pulseagent.security;

import com.pulseagent.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiting per client IP, with tighter limits for login (brute force)
 * and AI endpoints (cost control). Also rejects oversized request bodies early.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AppProperties.Limits limits;

    public RateLimitFilter(AppProperties props) {
        this.limits = props.limits();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getContentLengthLong() > limits.maxBodyBytes()) {
            write(res, 413, "{\"error\":\"request too large\"}");
            return;
        }
        String path = req.getRequestURI();
        if (path.startsWith("/api/")) {
            String ip = req.getRemoteAddr();
            String bucket;
            int limit;
            if (path.equals("/api/auth/login")) {
                bucket = "login";
                limit = limits.loginPerMinute();
            } else if (path.equals("/api/v1/errors")) {
                // Machine ingest has its own bucket so a noisy app can't lock people out of the UI.
                bucket = "ingest";
                limit = limits.ingestPerMinute();
            } else if ("POST".equals(req.getMethod()) && (path.startsWith("/api/investigations") || path.startsWith("/api/v1/investigations") || path.startsWith("/api/v1/webhooks"))) {
                bucket = "ai";
                limit = limits.aiPerMinute();
            } else {
                bucket = "api";
                limit = limits.requestsPerMinute();
            }
            if (!allow(bucket + "|" + ip, limit)) {
                res.setHeader("Retry-After", "60");
                write(res, 429, "{\"error\":\"rate limit exceeded\"}");
                return;
            }
        }
        chain.doFilter(new LimitedBodyRequest(req, limits.maxBodyBytes()), res);
    }

    private boolean allow(String key, int limit) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        if (windows.size() > 50_000) windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        return w.count().incrementAndGet() <= limit;
    }

    private static void write(HttpServletResponse res, int status, String body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write(body);
    }
}
