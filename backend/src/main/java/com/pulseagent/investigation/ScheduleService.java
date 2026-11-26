package com.pulseagent.investigation;

import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.AuditService;
import com.pulseagent.tenant.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Recurring health checks: a saved question that runs as an investigation every N minutes. */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);
    private static final int MAX_PER_WORKSPACE = 20;

    private final JdbcTemplate db;
    private final InvestigationService investigations;
    private final AuditService audit;

    public ScheduleService(JdbcTemplate db, InvestigationService investigations, AuditService audit) {
        this.db = db;
        this.investigations = investigations;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(String ws) {
        return db.queryForList("select id, name, question, service, interval_minutes, enabled, last_run_at, last_investigation_id, next_run_at from schedules where workspace_id = ? order by created_at", ws);
    }

    public String create(Actor by, String name, String question, String service, int intervalMinutes) {
        Integer n = db.queryForObject("select count(*) from schedules where workspace_id = ?", Integer.class, by.workspaceId());
        if (n != null && n >= MAX_PER_WORKSPACE) throw new IllegalArgumentException("A workspace can have up to " + MAX_PER_WORKSPACE + " scheduled checks");
        String id = "sch_" + Crypto.randomToken(9).replaceAll("[^A-Za-z0-9]", "x");
        Instant now = Instant.now();
        db.update("insert into schedules(id, workspace_id, name, question, service, interval_minutes, enabled, next_run_at, created_at) values (?,?,?,?,?,?,?,?,?)",
                id, by.workspaceId(), name, question, service, intervalMinutes, true, Timestamp.from(now.plusSeconds(60)), Timestamp.from(now));
        audit.log(by, "schedule.created", name + " every " + intervalMinutes + " min");
        return id;
    }

    public boolean setEnabled(Actor by, String id, boolean enabled) {
        boolean ok = db.update("update schedules set enabled = ? where workspace_id = ? and id = ?", enabled, by.workspaceId(), id) > 0;
        if (ok) audit.log(by, enabled ? "schedule.enabled" : "schedule.disabled", id);
        return ok;
    }

    public boolean delete(Actor by, String id) {
        boolean ok = db.update("delete from schedules where workspace_id = ? and id = ?", by.workspaceId(), id) > 0;
        if (ok) audit.log(by, "schedule.deleted", id);
        return ok;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    void tick() {
        Timestamp now = Timestamp.from(Instant.now());
        for (Map<String, Object> s : db.queryForList("select id, workspace_id, question, service, interval_minutes from schedules where enabled = true and next_run_at <= ? limit 20", now)) {
            int interval = ((Number) s.get("interval_minutes")).intValue();
            // Claim by moving next_run_at forward first, so a slow run can't trigger twice.
            int claimed = db.update("update schedules set next_run_at = ?, last_run_at = ? where id = ? and next_run_at <= ?",
                    Timestamp.from(Instant.now().plusSeconds(interval * 60L)), now, s.get("id"), now);
            if (claimed == 0) continue;
            try {
                Investigation inv = investigations.start((String) s.get("workspace_id"), (String) s.get("question"),
                        (String) s.get("service"), Math.min(Math.max(interval, 15), 1440), null, "schedule");
                db.update("update schedules set last_investigation_id = ? where id = ?", inv.id, s.get("id"));
            } catch (Exception e) {
                log.warn("Scheduled check {} could not start: {}", s.get("id"), e.getMessage());
            }
        }
    }
}
