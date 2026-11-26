package com.pulseagent.api;

import com.pulseagent.config.AppProperties;
import com.pulseagent.security.ApiKeyService;
import com.pulseagent.security.AuthFilter;
import com.pulseagent.security.SessionTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Exchanges the admin API key for an HttpOnly, SameSite=Strict session cookie. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 200) String apiKey) {}

    private final ApiKeyService keys;
    private final SessionTokenService sessions;
    private final boolean secure;

    public AuthController(ApiKeyService keys, SessionTokenService sessions, AppProperties props) {
        this.keys = keys;
        this.sessions = sessions;
        this.secure = props.security().secureCookies();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) throws InterruptedException {
        var role = keys.authenticate(req.apiKey());
        if (role.isEmpty() || role.get() != ApiKeyService.Role.ADMIN) {
            Thread.sleep(400); // slow down guessing on top of the rate limit
            return ResponseEntity.status(401).body(Map.of("error", "That key isn't valid."));
        }
        String token = sessions.issue("admin");
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(token, Duration.ofSeconds(sessions.ttlSeconds())).toString())
                .body(Map.of("ok", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) for (Cookie c : cookies) if (AuthFilter.COOKIE.equals(c.getName())) sessions.revoke(c.getValue());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).body(Map.of("ok", true));
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        return Map.of("authenticated", true, "principal", auth.getName());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
