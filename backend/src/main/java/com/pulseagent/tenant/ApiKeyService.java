package com.pulseagent.tenant;

import com.pulseagent.security.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Workspace API keys. Only SHA-256 hashes are stored; the plain key is shown once at creation.
 * Scopes: admin (full access), ingest (send errors and alerts only), widget (embedded chat only,
 * restricted to allowed browser origins).
 */
@Service
public class ApiKeyService {

    public static final Set<String> SCOPES = Set.of("admin", "ingest", "widget");

    public record KeyInfo(String id, String workspaceId, String name, String scope, List<String> allowedOrigins) {}

    public record Created(String id, String plainKey) {}

    private final JdbcTemplate db;

    public ApiKeyService(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<KeyInfo> authenticate(String presented) {
        if (presented == null || presented.length() < 20 || presented.length() > 200) return Optional.empty();
        String hash = Hashing.sha256Hex(presented.trim());
        // Lookup by hash of a 256-bit random key: no timing oracle on the key itself.
        List<KeyInfo> rows = db.query("select id, workspace_id, name, scope, allowed_origins from api_keys where key_hash = ? and revoked = false",
                (rs, i) -> new KeyInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), origins(rs.getString(5))), hash);
        if (rows.isEmpty()) return Optional.empty();
        db.update("update api_keys set last_used_at = ? where id = ?", Timestamp.from(Instant.now()), rows.get(0).id());
        return Optional.of(rows.get(0));
    }

    public boolean isActive(String keyId) {
        Integer n = db.queryForObject("select count(*) from api_keys where id = ? and revoked = false", Integer.class, keyId);
        return n != null && n > 0;
    }

    public Created create(String workspaceId, String name, String scope, List<String> allowedOrigins, String createdBy) {
        if (!SCOPES.contains(scope)) throw new IllegalArgumentException("Invalid scope");
        String plain = "pa_" + scope + "_" + Crypto.randomToken(32);
        String id = "key_" + Crypto.randomToken(9).replaceAll("[^A-Za-z0-9]", "x");
        insert(id, workspaceId, name, scope, Hashing.sha256Hex(plain), plain.substring(0, Math.min(plain.length(), 16)), allowedOrigins, createdBy);
        return new Created(id, plain);
    }

    /** Registers an existing key by hash (bootstrap from environment variables). */
    public void registerHash(String workspaceId, String name, String scope, String sha256Hex, String createdBy) {
        Integer n = db.queryForObject("select count(*) from api_keys where key_hash = ?", Integer.class, sha256Hex);
        if (n != null && n > 0) return;
        insert("key_env_" + scope, workspaceId, name, scope, sha256Hex, "pa_" + scope + "_…", List.of(), createdBy);
    }

    private void insert(String id, String ws, String name, String scope, String hash, String prefix, List<String> origins, String by) {
        db.update("insert into api_keys(id, workspace_id, name, scope, key_hash, prefix, allowed_origins, created_by, created_at) values (?,?,?,?,?,?,?,?,?)",
                id, ws, name, scope, hash, prefix, origins == null ? null : String.join(" ", origins), by, Timestamp.from(Instant.now()));
    }

    public List<Map<String, Object>> list(String workspaceId) {
        return db.queryForList("select id, name, scope, prefix, allowed_origins, created_by, created_at, last_used_at, revoked from api_keys where workspace_id = ? order by created_at desc", workspaceId);
    }

    public boolean revoke(String workspaceId, String id) {
        return db.update("update api_keys set revoked = true where workspace_id = ? and id = ?", workspaceId, id) > 0;
    }

    private static List<String> origins(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split("\\s+")).filter(x -> !x.isBlank()).toList();
    }
}
