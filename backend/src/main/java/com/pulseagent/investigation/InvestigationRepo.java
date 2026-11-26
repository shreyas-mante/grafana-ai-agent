package com.pulseagent.investigation;

import com.pulseagent.config.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable storage and job queue for investigations. */
@Repository
public class InvestigationRepo {

    private final JdbcTemplate db;

    public InvestigationRepo(JdbcTemplate db) {
        this.db = db;
    }

    public void insert(Investigation i) {
        db.update("insert into investigations(id, workspace_id, status, trigger_type, question, service, lookback_minutes, error_group_id, created_at, data) values (?,?,?,?,?,?,?,?,?,?)",
                i.id, i.workspaceId, i.status().name(), i.trigger, i.question, i.service, i.lookbackMinutes, i.errorGroupId,
                Timestamp.from(i.createdAt), JsonUtil.write(i.toJson(true)));
    }

    public void save(Investigation i) {
        var j = i.toJson(true);
        db.update("update investigations set status = ?, title = ?, confidence = ?, finished_at = ?, data = ? where id = ?",
                i.status().name(), JsonUtil.truncate(j.path("title").asText(null), 290), j.path("confidence").asText(null),
                i.finishedAt() == null ? null : Timestamp.from(i.finishedAt()), JsonUtil.write(j), i.id);
    }

    public Optional<Investigation> load(String ws, String id) {
        List<Investigation> r = db.query("select id, workspace_id, question, service, lookback_minutes, error_group_id, trigger_type, created_at, data from investigations where workspace_id = ? and id = ?",
                (rs, n) -> {
                    Investigation inv = new Investigation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                            rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant());
                    try {
                        inv.restore(JsonUtil.M.readTree(rs.getString(9)));
                    } catch (Exception ignored) {
                        // corrupted data: return the bare record
                    }
                    return inv;
                }, ws, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public List<Map<String, Object>> list(String ws, int limit) {
        return db.queryForList("select id, question, service, trigger_type, status, title, confidence, created_at, finished_at from investigations where workspace_id = ? order by created_at desc limit ?",
                ws, limit);
    }

    /** Claims up to n queued jobs (oldest first). Safe with several app instances: the update is conditional. */
    public List<String[]> claim(int n) {
        List<String[]> candidates = db.query("select id, workspace_id from investigations where status = 'QUEUED' order by created_at limit ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, n);
        return candidates.stream()
                .filter(c -> db.update("update investigations set status = 'RUNNING', attempts = attempts + 1 where id = ? and status = 'QUEUED'", c[0]) == 1)
                .toList();
    }

    /** After a crash or restart: retry interrupted jobs up to 3 times, then mark them failed. */
    public int recover() {
        int failed = db.update("update investigations set status = 'FAILED', finished_at = ? where status = 'RUNNING' and attempts >= 3", Timestamp.from(Instant.now()));
        int requeued = db.update("update investigations set status = 'QUEUED' where status = 'RUNNING'");
        return failed + requeued;
    }

    public int countRecent(String ws, String trigger, int minutes) {
        Integer n = db.queryForObject("select count(*) from investigations where workspace_id = ? and trigger_type = ? and created_at > ?",
                Integer.class, ws, trigger, Timestamp.from(Instant.now().minusSeconds(minutes * 60L)));
        return n == null ? 0 : n;
    }

    public int pruneOld(int keepDays) {
        return db.update("delete from investigations where created_at < ? and status in ('COMPLETED','FAILED')",
                Timestamp.from(Instant.now().minusSeconds(keepDays * 86400L)));
    }
}
