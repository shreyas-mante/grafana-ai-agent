package com.pulseagent.api;

import com.pulseagent.config.AppProperties;
import com.pulseagent.security.AuthFilter;
import com.pulseagent.security.SessionTokenService;
import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.ApiKeyService;
import com.pulseagent.tenant.AuditService;
import com.pulseagent.tenant.UserService;
import com.pulseagent.tenant.WorkspaceService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sign-up, sign-in (email + password, or an admin API key), workspace switching, sign-out. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@Size(max = 254) String email, @Size(max = 128) String password, @Size(max = 200) String apiKey) {}

    public record SignupRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password,
                                @Size(max = 100) String workspaceName, @Size(max = 100) String inviteToken) {}

    public record SwitchRequest(@NotBlank @Size(max = 40) String workspaceId) {}

    public record JoinRequest(@NotBlank @Size(max = 100) String inviteToken) {}

    private static final String GENERIC = "That email and password don't match.";

    private final ApiKeyService keys;
    private final SessionTokenService sessions;
    private final UserService users;
    private final WorkspaceService workspaces;
    private final AuditService audit;
    private final AppProperties props;

    public AuthController(ApiKeyService keys, SessionTokenService sessions, UserService users, WorkspaceService workspaces,
                          AuditService audit, AppProperties props) {
        this.keys = keys;
        this.sessions = sessions;
        this.users = users;
        this.workspaces = workspaces;
        this.audit = audit;
        this.props = props;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("signup", props.platform().signup());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) throws InterruptedException {
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            var info = keys.authenticate(req.apiKey());
            if (info.isEmpty() || !"admin".equals(info.get().scope())) {
                Thread.sleep(400);
                return ResponseEntity.status(401).body(Map.of("error", "That key isn't valid."));
            }
            audit.log(info.get().workspaceId(), "API key: " + info.get().name(), "login", "via admin key");
            return session(Map.of("kind", "key", "sub", info.get().id(), "ws", info.get().workspaceId(), "name", "API key: " + info.get().name()));
        }
        var user = users.authenticate(req.email(), req.password());
        if (user.isEmpty()) {
            Thread.sleep(300);
            return ResponseEntity.status(401).body(Map.of("error", GENERIC));
        }
        var ws = users.firstWorkspace(user.get().id());
        if (ws.isEmpty()) return ResponseEntity.status(403).body(Map.of("error", "Your account isn't in any workspace. Ask an owner for an invite."));
        audit.log(ws.get(), user.get().email(), "login", "password");
        return session(Map.of("kind", "user", "sub", user.get().id(), "ws", ws.get(), "name", user.get().email()));
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest req) {
        var m = users.signup(req.email(), req.password(), req.workspaceName(), req.inviteToken());
        var user = users.authenticate(req.email(), req.password()).orElseThrow();
        return session(Map.of("kind", "user", "sub", user.id(), "ws", m.workspaceId(), "name", user.email()));
    }

    /** Lets a signed-in user accept an invite to another workspace. */
    @PostMapping("/join")
    public ResponseEntity<?> join(@Valid @RequestBody JoinRequest req) {
        Actor a = Actor.current();
        if (!"session".equals(a.via()) || a.id().startsWith("key:")) return ResponseEntity.status(403).body(Map.of("error", "Sign in with an account to join a workspace"));
        users.joinWithInvite(a.id(), req.inviteToken());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/switch")
    public ResponseEntity<?> switchWorkspace(@Valid @RequestBody SwitchRequest req) {
        Actor a = Actor.current();
        if (a.id().startsWith("key:")) return ResponseEntity.status(403).body(Map.of("error", "API-key sessions are tied to one workspace"));
        if (users.role(a.id(), req.workspaceId()).isEmpty()) return ResponseEntity.status(403).body(Map.of("error", "forbidden"));
        return session(Map.of("kind", "user", "sub", a.id(), "ws", req.workspaceId(), "name", a.display()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) for (Cookie c : cookies) if (AuthFilter.COOKIE.equals(c.getName())) sessions.revoke(c.getValue());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).body(Map.of("ok", true));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        Actor a = Actor.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.display());
        m.put("role", a.role().name());
        m.put("workspaceId", a.workspaceId());
        m.put("workspaceName", workspaces.name(a.workspaceId()));
        m.put("workspaces", a.id().startsWith("key:") ? List.of(Map.of("id", a.workspaceId(), "name", String.valueOf(workspaces.name(a.workspaceId())), "role", a.role().name()))
                : workspaces.forUser(a.id()));
        return m;
    }

    private ResponseEntity<?> session(Map<String, String> claims) {
        String token = sessions.issue(claims);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(token, Duration.ofSeconds(sessions.ttlSeconds())).toString())
                .body(Map.of("ok", true));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true)
                .secure(props.security().secureCookies())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
