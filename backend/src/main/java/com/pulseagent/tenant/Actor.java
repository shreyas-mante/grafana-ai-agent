package com.pulseagent.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Who is making the current request, and in which workspace. Every data access is scoped
 * to {@link #workspaceId()}, which comes only from verified credentials, never from input.
 */
public record Actor(String workspaceId, String id, String display, Role role, String via, List<String> allowedOrigins) {

    public enum Role { OWNER, MEMBER, INGEST, WIDGET }

    public boolean isOwner() { return role == Role.OWNER; }

    public static Actor current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Actor actor) return actor;
        throw new IllegalStateException("No authenticated actor");
    }

    public static Actor system(String workspaceId, String what) {
        return new Actor(workspaceId, "system", what, Role.OWNER, "system", List.of());
    }
}
