package com.pulseagent.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.net.HttpClients;
import com.pulseagent.net.OutboundGuard;
import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.AuditService;
import com.pulseagent.tenant.Crypto;
import com.pulseagent.tenant.SettingsService;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Approval-gated remediation. The agent can only PROPOSE an action; nothing runs until a
 * workspace owner approves it. Execution is a separate, audited path:
 *  - webhook: POSTs the action to the workspace's own automation, signed with HMAC-SHA256
 *  - github_issue: opens an issue with the findings (needs a separate write token)
 */
@Service
public class ActionService {

    private final JdbcTemplate db;
    private final SettingsService settings;
    private final AuditService audit;
    private final String publicUrl;

    public ActionService(JdbcTemplate db, SettingsService settings, AuditService audit, com.pulseagent.config.AppProperties props) {
        this.db = db;
        this.settings = settings;
        this.audit = audit;
        this.publicUrl = props.platform().publicUrl() == null ? "" : props.platform().publicUrl().replaceAll("/+$", "");
    }

    public String propose(String ws, String investigationId, String type, String title, ObjectNode payload, String reason) {
        String id = "act_" + Crypto.randomToken(9).replaceAll("[^A-Za-z0-9]", "x");
        db.update("insert into actions(id, workspace_id, investigation_id, type, title, payload, reason, status, proposed_at) values (?,?,?,?,?,?,?,?,?)",
                id, ws, investigationId, type, JsonUtil.truncate(title, 290), JsonUtil.write(payload), JsonUtil.truncate(reason, 1900),
                "PENDING", Timestamp.from(Instant.now()));
        audit.log(ws, "agent", "action.proposed", type + ": " + title);
        return id;
    }

    public List<Map<String, Object>> list(String ws) {
        return db.queryForList("select id, investigation_id, type, title, payload, reason, status, proposed_at, decided_by, decided_at, result from actions where workspace_id = ? order by proposed_at desc limit 200", ws);
    }

    public Optional<String> reject(Actor by, String id) {
        int n = db.update("update actions set status = 'REJECTED', decided_by = ?, decided_at = ? where workspace_id = ? and id = ? and status = 'PENDING'",
                by.display(), Timestamp.from(Instant.now()), by.workspaceId(), id);
        if (n == 0) return Optional.of("This action isn't pending anymore.");
        audit.log(by, "action.rejected", id);
        return Optional.empty();
    }

    /** Atomically claims a pending action, then executes it. Returns the result text. */
    public String approve(Actor by, String id) {
        int n = db.update("update actions set status = 'APPROVED', decided_by = ?, decided_at = ? where workspace_id = ? and id = ? and status = 'PENDING'",
                by.display(), Timestamp.from(Instant.now()), by.workspaceId(), id);
        if (n == 0) throw new IllegalArgumentException("This action isn't pending anymore.");
        Map<String, Object> row = db.queryForMap("select type, title, payload, reason, investigation_id from actions where id = ?", id);
        String result;
        String status;
        try {
            JsonNode payload = JsonUtil.M.readTree((String) row.get("payload"));
            result = execute(by.workspaceId(), id, (String) row.get("type"), (String) row.get("title"), payload, (String) row.get("reason"),
                    (String) row.get("investigation_id"), by.display());
            status = "EXECUTED";
        } catch (Exception e) {
            result = "Failed: " + JsonUtil.truncate(e.getMessage(), 300);
            status = "FAILED";
        }
        db.update("update actions set status = ?, result = ? where id = ?", status, JsonUtil.truncate(result, 1900), id);
        audit.log(by, "action." + status.toLowerCase(), id + ": " + result);
        return result;
    }

    private String execute(String ws, String id, String type, String title, JsonNode payload, String reason, String invId, String approver) throws Exception {
        if ("github_issue".equals(type)) {
            String repo = settings.get(ws, "github.repo");
            String token = settings.get(ws, "github.writeToken");
            if (repo == null || token == null) throw new IllegalStateException("Set the GitHub repository and issues write token first");
            ObjectNode body = JsonUtil.obj();
            body.put("title", JsonUtil.truncate(title, 250));
            body.put("body", "**Opened by PulseAgent after approval by " + approver + "**\n\n" + JsonUtil.truncate(reason, 3000)
                    + "\n\n```\n" + JsonUtil.truncate(payload.path("details").asText(""), 4000) + "\n```\n\nInvestigation: "
                    + publicUrl + "/#/inv/" + invId);
            JsonNode res = HttpClients.build("https://api.github.com", null, token, 20).post()
                    .uri("/repos/" + repo + "/issues").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            return "Opened issue #" + (res == null ? "?" : res.path("number").asText()) + " in " + repo;
        }
        if ("webhook".equals(type)) {
            String url = settings.get(ws, "actions.webhookUrl");
            String secret = settings.get(ws, "actions.webhookSecret");
            if (url == null || secret == null) throw new IllegalStateException("Set the remediation webhook URL and signing secret first");
            OutboundGuard.check(url, settings.allowPrivate(ws, "actions.webhookUrl"));
            ObjectNode body = JsonUtil.obj();
            body.put("actionId", id).put("investigationId", invId).put("title", title).put("reason", reason).put("approvedBy", approver);
            body.set("action", payload);
            String json = JsonUtil.write(body);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = HexFormat.of().formatHex(mac.doFinal((ts + "." + json).getBytes(StandardCharsets.UTF_8)));
            var res = HttpClients.build(url, null, null, 20).post().uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-PulseAgent-Timestamp", ts)
                    .header("X-PulseAgent-Signature", "sha256=" + sig)
                    .body(json).retrieve().toBodilessEntity();
            return "Webhook accepted the action (HTTP " + res.getStatusCode().value() + ")";
        }
        throw new IllegalStateException("Unknown action type");
    }
}
