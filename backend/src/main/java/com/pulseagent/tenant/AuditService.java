package com.pulseagent.tenant;

import com.pulseagent.config.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Append-only audit trail of security-relevant actions per workspace. */
@Service
public class AuditService {

    private final JdbcTemplate db;

    public AuditService(JdbcTemplate db) {
        this.db = db;
    }

    public void log(String workspaceId, String actor, String action, String detail) {
        db.update("insert into audit_events(workspace_id, actor, action, detail, at) values (?,?,?,?,?)",
                workspaceId, JsonUtil.truncate(actor, 120), action, JsonUtil.truncate(detail, 1900), Timestamp.from(Instant.now()));
    }

    public void log(Actor a, String action, String detail) {
        log(a.workspaceId(), a.display(), action, detail);
    }

    public List<Map<String, Object>> list(String workspaceId, int limit) {
        return db.queryForList("select actor, action, detail, at from audit_events where workspace_id = ? order by at desc limit ?",
                workspaceId, Math.min(limit, 500));
    }
}
