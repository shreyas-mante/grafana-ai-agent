package com.pulseagent.security;

import com.pulseagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Validates API keys against SHA-256 hashes from the environment. Plain keys are never stored.
 * Comparison is constant-time to avoid timing attacks. Missing configuration fails closed.
 */
@Service
public class ApiKeyService {

    public enum Role { ADMIN, INGEST }

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private final byte[] adminHash;
    private final byte[] ingestHash;

    public ApiKeyService(AppProperties props) {
        this.adminHash = parse(props.security().adminKeySha256(), "APP_ADMIN_KEY_SHA256");
        this.ingestHash = parse(props.security().ingestKeySha256(), "APP_INGEST_KEY_SHA256");
    }

    private static byte[] parse(String hex, String name) {
        if (hex == null || hex.isBlank()) {
            log.warn("{} is not set: that key type is disabled (fail closed).", name);
            return null;
        }
        String h = hex.trim().toLowerCase();
        if (!h.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(name + " must be a 64-character SHA-256 hex digest");
        }
        return HexFormat.of().parseHex(h);
    }

    public Optional<Role> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.length() < 20 || presentedKey.length() > 200) return Optional.empty();
        byte[] digest = sha256(presentedKey.trim());
        boolean admin = adminHash != null && MessageDigest.isEqual(digest, adminHash);
        boolean ingest = ingestHash != null && MessageDigest.isEqual(digest, ingestHash);
        if (admin) return Optional.of(Role.ADMIN);
        if (ingest) return Optional.of(Role.INGEST);
        return Optional.empty();
    }

    public static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        return HexFormat.of().formatHex(sha256(s));
    }
}
