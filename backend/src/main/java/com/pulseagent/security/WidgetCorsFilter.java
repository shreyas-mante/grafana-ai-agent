package com.pulseagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CORS only for the embeddable widget API. Preflights are answered generically (no data);
 * real requests are then authorised per widget key against that key's allowed origins.
 * No cookies are ever accepted cross-origin.
 */
public class WidgetCorsFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String origin = req.getHeader("Origin");
        if (req.getRequestURI().startsWith("/api/widget/") && origin != null && origin.matches("https?://[A-Za-z0-9.:-]{1,253}")) {
            res.setHeader("Access-Control-Allow-Origin", origin);
            res.setHeader("Vary", "Origin");
            if ("OPTIONS".equals(req.getMethod())) {
                res.setHeader("Access-Control-Allow-Methods", "GET, POST");
                res.setHeader("Access-Control-Allow-Headers", "Content-Type, X-Widget-Key");
                res.setHeader("Access-Control-Max-Age", "600");
                res.setStatus(204);
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
