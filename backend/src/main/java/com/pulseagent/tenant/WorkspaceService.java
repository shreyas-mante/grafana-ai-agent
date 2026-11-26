package com.pulseagent.tenant;

import com.pulseagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);
    private final JdbcTemplate db;
    private final AppProperties props;
    private final ApiKeyService keys;
    private final AuditService audit;

    public WorkspaceService(JdbcTemplate db, AppProperties props, ApiKeyService keys, AuditService audit) {
        this.db = db;
        this.props = props;
        this.keys = keys;
        this.audit = audit;
    }

    /**
     * Bootstrap: if admin/ingest key hashes are set in the environment, make sure the default
     * workspace exists and those keys work, so single-tenant and local setups keep working.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void bootstrap() {
        String admin = props.security().adminKeySha256();
        String ingest = props.security().ingestKeySha256();
        boolean hasEnvKeys = (admin != null && !admin.isBlank()) || (ingest != null && !ingest.isBlank());
        if (!hasEnvKeys) return;
        if (!exists(SettingsService.DEFAULT_WS)) {
            create(SettingsService.DEFAULT_WS, "Default workspace", "bootstrap");
            log.info("Created the default workspace from environment configuration");
        }
        if (admin != null && admin.matches("[0-9a-fA-F]{64}")) keys.registerHash(SettingsService.DEFAULT_WS, "Admin key (environment)", "admin", admin.toLowerCase(Locale.ROOT), "bootstrap");
        if (ingest != null && ingest.matches("[0-9a-fA-F]{64}")) keys.registerHash(SettingsService.DEFAULT_WS, "Ingest key (environment)", "ingest", ingest.toLowerCase(Locale.ROOT), "bootstrap");
    }

    public boolean exists(String id) {
        Integer n = db.queryForObject("select count(*) from workspaces where id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public String create(String name, String createdBy) {
        return create("ws_" + Crypto.randomToken(9).replaceAll("[^A-Za-z0-9]", "x").toLowerCase(Locale.ROOT), name, createdBy);
    }

    private String create(String id, String name, String createdBy) {
        db.update("insert into workspaces(id, name, created_at) values (?,?,?)", id, name, Timestamp.from(Instant.now()));
        seedKnowledge(id);
        audit.log(id, createdBy, "workspace.created", name);
        return id;
    }

    public String name(String id) {
        List<String> n = db.queryForList("select name from workspaces where id = ?", String.class, id);
        return n.isEmpty() ? null : n.get(0);
    }

    public List<Map<String, Object>> forUser(String userId) {
        return db.queryForList("select w.id, w.name, m.role from workspaces w join memberships m on m.workspace_id = w.id where m.user_id = ? order by w.created_at", userId);
    }

    /** Copies the starter runbooks and incidents into a new workspace's knowledge base. */
    private void seedKnowledge(String ws) {
        Path dir = Path.of(props.knowledge().dir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir, 3)) {
            for (Path f : s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String content = Files.readString(f, StandardCharsets.UTF_8);
                String type = f.toString().contains("/runbooks/") ? "runbook" : f.toString().contains("/incidents/") ? "incident" : "doc";
                String id = f.getFileName().toString().replace(".md", "");
                String title = content.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).trim()).orElse(id);
                db.update("insert into kb_docs(workspace_id, id, title, type, source, content, created_at) values (?,?,?,?,?,?,?)",
                        ws, id, title.length() > 200 ? title.substring(0, 200) : title, type, "starter", content, Timestamp.from(Instant.now()));
            }
        } catch (Exception e) {
            log.warn("Could not seed knowledge base: {}", e.getMessage());
        }
    }
}
