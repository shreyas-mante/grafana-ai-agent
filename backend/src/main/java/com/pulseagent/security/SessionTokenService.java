package com.pulseagent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, HMAC-SHA256 signed session tokens for the web UI, stored in an HttpOnly cookie.
 * Logout revokes the token id until it would have expired anyway.
 */
@Service
public class SessionTokenService {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlSeconds;
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    public SessionTokenService(AppProperties props) {
        String s = props.security().sessionSecret();
        if (s == null || s.length() < 32) {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("APP_SESSION_SECRET is missing or shorter than 32 chars: using a random secret. Sessions reset on restart.");
        } else {
            this.secret = s.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = Math.max(1, props.security().sessionHours()) * 3600L;
    }

    public long ttlSeconds() { return ttlSeconds; }

    public String issue(Map<String, String> claims) {
        Map<String, Object> payload = new HashMap<>(claims);
        payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
        payload.put("jti", UUID.randomUUID().toString());
        String p = ENC.encodeToString(JsonUtil.write(payload).getBytes(StandardCharsets.UTF_8));
        return p + "." + ENC.encodeToString(hmac(p));
    }

    /** Returns the claims if the token is authentic, unexpired and not revoked; otherwise null. */
    public Map<String, String> verify(String token) {
        if (token == null || token.length() > 2048) return null;
        int dot = token.indexOf('.');
        if (dot <= 0) return null;
        String p = token.substring(0, dot);
        byte[] sig;
        try {
            sig = DEC.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(sig, hmac(p))) return null;
        try {
            JsonNode n = JsonUtil.M.readTree(DEC.decode(p));
            if (Instant.now().getEpochSecond() >= n.path("exp").asLong(0)) return null;
            if (revoked.containsKey(n.path("jti").asText())) return null;
            Map<String, String> out = new HashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public void revoke(String token) {
        Map<String, String> c = verify(token);
        if (c == null) return;
        revoked.put(c.get("jti"), Long.parseLong(c.get("exp")));
        long now = Instant.now().getEpochSecond();
        revoked.entrySet().removeIf(e -> e.getValue() < now);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
