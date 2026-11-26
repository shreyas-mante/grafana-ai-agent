package com.pulseagent.notify;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.investigation.Investigation;
import com.pulseagent.net.HttpClients;
import com.pulseagent.tenant.SettingsService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Posts finished investigations to the workspace's Slack incoming webhook. By default only
 * automatic investigations (alerts, errors, schedules) notify; "all" includes manual ones.
 */
@Service
public class NotificationService {

    private final SettingsService settings;
    private final String publicUrl;

    public NotificationService(SettingsService settings, AppProperties props) {
        this.settings = settings;
        this.publicUrl = props.platform().publicUrl() == null ? "" : props.platform().publicUrl().replaceAll("/+$", "");
    }

    public void investigationFinished(Investigation inv) {
        String url = settings.get(inv.workspaceId, "slack.webhookUrl");
        if (url == null || !url.startsWith("https://hooks.slack.com/services/")) return;
        String policy = settings.getOr(inv.workspaceId, "notify.on", "alerts");
        boolean automatic = !"user".equals(inv.trigger) && !"widget".equals(inv.trigger);
        if ("none".equals(policy) || ("alerts".equals(policy) && !automatic)) return;
        post(url, message(inv));
    }

    public void test(String ws) {
        String url = settings.get(ws, "slack.webhookUrl");
        if (url == null) throw new IllegalArgumentException("Set the Slack webhook URL first");
        ObjectNode m = JsonUtil.obj();
        m.put("text", "PulseAgent is connected. Finished investigations will be posted here.");
        post(url, m);
    }

    private ObjectNode message(Investigation inv) {
        ObjectNode r = inv.report();
        String link = publicUrl + "/#/inv/" + inv.id;
        String title = r != null ? r.path("title").asText(inv.question) : inv.question;
        String status = inv.status() == Investigation.Status.COMPLETED
                ? "Confidence: " + (r == null ? "unknown" : r.path("confidence").asText("unknown")) : "Investigation failed";
        StringBuilder body = new StringBuilder();
        if (r != null) {
            body.append("*Likely cause:* ").append(esc(r.path("likelyCause").asText("—"))).append("\n");
            if (r.path("codeFinding").has("file")) {
                body.append("*Code:* `").append(esc(r.path("codeFinding").path("file").asText())).append(":")
                        .append(r.path("codeFinding").path("line").asInt()).append("`\n");
            }
            if (r.path("nextSteps").isArray() && !r.path("nextSteps").isEmpty()) {
                body.append("*Next step:* ").append(esc(r.path("nextSteps").get(0).asText())).append("\n");
            }
        }
        ObjectNode m = JsonUtil.obj();
        m.put("text", "PulseAgent: " + title);
        ArrayNode blocks = m.putArray("blocks");
        blocks.addObject().put("type", "header").putObject("text").put("type", "plain_text").put("text", JsonUtil.truncate(title, 140));
        blocks.addObject().put("type", "section").putObject("text").put("type", "mrkdwn")
                .put("text", JsonUtil.truncate(esc(status) + " · trigger: " + inv.trigger + (inv.service != null ? " · " + esc(inv.service) : "") + "\n" + body, 2900));
        blocks.addObject().put("type", "section").putObject("text").put("type", "mrkdwn").put("text", "<" + link + "|Open the investigation>");
        return m;
    }

    /** Escapes Slack mrkdwn control characters so report text can't inject links or mentions. */
    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void post(String url, ObjectNode body) {
        HttpClients.build(url, null, null, 10).post().uri("").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }
}
