package com.pulseagent.errors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.security.Hashing;
import com.pulseagent.security.Redactor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Error groups per workspace, stored in the database. Messages are redacted before storage. */
@Service
public class ErrorStore {

    private static final int MAX_GROUPS_PER_WORKSPACE = 1000;
    private final JdbcTemplate db;

    public ErrorStore(JdbcTemplate db) {
        this.db = db;
    }

    public ErrorGroup record(String ws, ErrorEventRequest e, List<String> appPackages) {
        List<ErrorEventRequest.Frame> top = appFrames(e.frames(), appPackages, 1);
        ErrorEventRequest.Frame f = !top.isEmpty() ? top.get(0) : e.frames().isEmpty() ? null : e.frames().get(0);
        String fingerprint = e.service() + "|" + e.exceptionClass() + "|" + (f == null ? "" : f.className() + "." + f.methodName() + ":" + f.lineNumber());
        String id = "err_" + Hashing.sha256Hex(fingerprint).substring(0, 16);

        ObjectNode data = JsonUtil.obj();
        data.put("environment", e.environment()).put("exceptionClass", e.exceptionClass())
                .put("message", JsonUtil.truncate(Redactor.redact(e.message()), 2000))
                .put("httpMethod", e.httpMethod()).put("path", Redactor.redact(e.path()));
        data.set("frames", JsonUtil.M.valueToTree(e.frames()));
        data.set("causes", JsonUtil.M.valueToTree(e.causes() == null ? List.of() : e.causes().stream()
                .map(c -> new ErrorEventRequest.Cause(c.exceptionClass(), Redactor.redact(c.message()))).toList()));
        Timestamp now = Timestamp.from(Instant.now());

        Optional<ErrorGroup> existing = get(ws, id);
        String sha = e.commitSha() == null || e.commitSha().isBlank() ? existing.map(ErrorGroup::commitSha).orElse(null) : e.commitSha();
        data.put("commitSha", sha);
        if (existing.isPresent()) {
            db.update("update error_groups set count = count + 1, last_seen = ?, data = ? where workspace_id = ? and id = ?",
                    now, JsonUtil.write(data), ws, id);
        } else {
            Integer n = db.queryForObject("select count(*) from error_groups where workspace_id = ?", Integer.class, ws);
            if (n != null && n >= MAX_GROUPS_PER_WORKSPACE) {
                db.update("delete from error_groups where workspace_id = ? and id = (select id from error_groups where workspace_id = ? order by last_seen limit 1)", ws, ws);
            }
            try {
                db.update("insert into error_groups(workspace_id, id, service, count, first_seen, last_seen, data) values (?,?,?,?,?,?,?)",
                        ws, id, e.service(), 1, now, now, JsonUtil.write(data));
            } catch (DuplicateKeyException race) {
                db.update("update error_groups set count = count + 1, last_seen = ? where workspace_id = ? and id = ?", now, ws, id);
            }
        }
        return get(ws, id).orElseThrow();
    }

    public Optional<ErrorGroup> get(String ws, String id) {
        List<ErrorGroup> r = db.query("select id, service, count, first_seen, last_seen, data from error_groups where workspace_id = ? and id = ?", this::map, ws, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public List<ErrorGroup> recent(String ws, String service, int limit) {
        if (service == null || service.isBlank()) {
            return db.query("select id, service, count, first_seen, last_seen, data from error_groups where workspace_id = ? order by last_seen desc limit ?", this::map, ws, limit);
        }
        return db.query("select id, service, count, first_seen, last_seen, data from error_groups where workspace_id = ? and lower(service) = lower(?) order by last_seen desc limit ?",
                this::map, ws, service, limit);
    }

    /** Up to n frames from the customer's own code (by package prefix), from distinct classes, innermost first. */
    public static List<ErrorEventRequest.Frame> appFrames(List<ErrorEventRequest.Frame> frames, List<String> packages, int n) {
        if (frames == null) return List.of();
        Set<String> seen = new HashSet<>();
        List<ErrorEventRequest.Frame> out = new ArrayList<>();
        for (ErrorEventRequest.Frame f : frames) {
            if (f.lineNumber() <= 0) continue;
            if (!packages.isEmpty() && packages.stream().noneMatch(p -> f.className().startsWith(p))) continue;
            if (!seen.add(f.className().replaceAll("\\$.*", ""))) continue;
            out.add(f);
            if (out.size() >= n) break;
        }
        return out;
    }

    private ErrorGroup map(ResultSet rs, int i) throws SQLException {
        JsonNode d;
        try {
            d = JsonUtil.M.readTree(rs.getString(6));
        } catch (Exception e) {
            d = JsonUtil.obj();
        }
        List<ErrorEventRequest.Frame> frames = new ArrayList<>();
        for (JsonNode f : d.path("frames")) {
            frames.add(new ErrorEventRequest.Frame(f.path("className").asText(), f.path("methodName").asText(), f.path("fileName").asText(), f.path("lineNumber").asInt()));
        }
        List<ErrorEventRequest.Cause> causes = new ArrayList<>();
        for (JsonNode c : d.path("causes")) causes.add(new ErrorEventRequest.Cause(c.path("exceptionClass").asText(), c.path("message").asText()));
        return new ErrorGroup(rs.getString(1), rs.getString(2), d.path("environment").asText(null), d.path("exceptionClass").asText(),
                d.path("message").asText(null), d.path("httpMethod").asText(null), d.path("path").asText(null),
                d.path("commitSha").asText(null), rs.getLong(3), rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), frames, causes);
    }
}
