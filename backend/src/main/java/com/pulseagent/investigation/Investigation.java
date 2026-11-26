package com.pulseagent.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.code.CodeModels.CodeContext;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.tools.ToolContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One investigation: inputs, live steps, verified artefacts, report and chat. Its whole state
 * serialises to JSON (stored in the database after every step), so it survives restarts.
 */
public class Investigation implements ToolContext {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    public final String id;
    public final String workspaceId;
    public final String question;
    public final String service;
    public final int lookbackMinutes;
    public final String errorGroupId;
    public final String trigger;
    public final Instant createdAt;

    private Status status = Status.QUEUED;
    private String mode;
    private String model;
    private String failure;
    private Instant finishedAt;
    private ObjectNode report;
    private final ArrayNode steps = JsonUtil.arr();
    private final List<ObjectNode> charts = new ArrayList<>();
    private final Map<String, JsonNode> code = new LinkedHashMap<>();
    private final Map<String, JsonNode> knowledge = new LinkedHashMap<>();
    private final List<String> actions = new ArrayList<>();
    private final ArrayNode chat = JsonUtil.arr();

    public Investigation(String id, String workspaceId, String question, String service, int lookbackMinutes,
                         String errorGroupId, String trigger, Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.question = question;
        this.service = service;
        this.lookbackMinutes = lookbackMinutes;
        this.errorGroupId = errorGroupId;
        this.trigger = trigger;
        this.createdAt = createdAt;
    }

    @Override public String workspaceId() { return workspaceId; }

    @Override public String investigationId() { return id; }

    public synchronized void start(String mode, String model) {
        this.status = Status.RUNNING;
        this.mode = mode;
        this.model = model;
    }

    public synchronized void setStatus(Status s) { this.status = s; }

    public synchronized int addStep(String tool, JsonNode args) {
        ObjectNode s = steps.addObject();
        s.put("index", steps.size() - 1).put("tool", tool).put("status", "running");
        s.set("args", args);
        return steps.size() - 1;
    }

    public synchronized void finishStep(int i, String status, String summary, long ms) {
        ObjectNode s = (ObjectNode) steps.get(i);
        s.put("status", status).put("summary", summary).put("durationMs", ms);
    }

    public synchronized int stepCount() { return steps.size(); }

    public synchronized void complete(ObjectNode report) {
        this.report = report;
        this.status = Status.COMPLETED;
        this.finishedAt = Instant.now();
    }

    public synchronized void fail(String message) {
        this.failure = message;
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
    }

    public synchronized Status status() { return status; }

    public synchronized String mode() { return mode; }

    public synchronized ObjectNode report() { return report; }

    public synchronized Instant finishedAt() { return finishedAt; }

    public synchronized void addChat(String role, String text) {
        chat.addObject().put("role", role).put("text", text).put("at", Instant.now().toString());
        while (chat.size() > 40) chat.remove(0);
    }

    public synchronized List<String[]> chatHistory() {
        List<String[]> out = new ArrayList<>();
        chat.forEach(m -> out.add(new String[]{m.path("role").asText(), m.path("text").asText()}));
        return out;
    }

    @Override
    public synchronized void attachChart(String title, String query, JsonNode points, JsonNode summary) {
        if (charts.size() >= 8) return;
        ObjectNode c = JsonUtil.obj();
        c.put("title", title).put("query", query);
        c.set("points", points);
        c.set("summary", summary);
        charts.add(c);
    }

    @Override
    public synchronized void attachCode(String key, CodeContext ctx) {
        code.put(key, JsonUtil.M.valueToTree(ctx));
    }

    @Override
    public synchronized void attachKnowledge(List<KnowledgeBase.Hit> hits) {
        for (KnowledgeBase.Hit h : hits) {
            JsonNode old = knowledge.get(h.docId());
            if (old == null || old.path("score").asDouble() < h.score()) knowledge.put(h.docId(), JsonUtil.M.valueToTree(h));
        }
    }

    @Override
    public synchronized void attachAction(String actionId) {
        actions.add(actionId);
    }

    /** Compact evidence bundle used as context for follow-up chat. */
    public synchronized String contextForChat() {
        ObjectNode o = JsonUtil.obj();
        o.put("question", question).put("service", service).put("lookbackMinutes", lookbackMinutes);
        if (report != null) o.set("report", report);
        ArrayNode c = o.putArray("code");
        code.values().forEach(x -> {
            ObjectNode y = x.deepCopy();
            y.remove("lineCommits");
            c.add(y);
        });
        ArrayNode k = o.putArray("knowledge");
        knowledge.values().forEach(h -> k.addObject().put("title", h.path("title").asText()).put("type", h.path("type").asText())
                .put("excerpt", JsonUtil.truncate(h.path("excerpt").asText(), 500)));
        ArrayNode ch = o.putArray("charts");
        charts.forEach(x -> ch.addObject().put("title", x.path("title").asText()).set("summary", x.path("summary")));
        return JsonUtil.truncate(JsonUtil.write(o), 20_000);
    }

    public synchronized ObjectNode toJson(boolean full) {
        ObjectNode o = JsonUtil.obj();
        o.put("id", id).put("question", question).put("service", service).put("lookbackMinutes", lookbackMinutes)
                .put("trigger", trigger).put("status", status.name()).put("mode", mode).put("model", model)
                .put("createdAt", createdAt.toString())
                .put("finishedAt", finishedAt == null ? null : finishedAt.toString())
                .put("durationSeconds", finishedAt == null ? null : (finishedAt.toEpochMilli() - createdAt.toEpochMilli()) / 1000.0)
                .put("stepCount", steps.size())
                .put("failure", failure)
                .put("errorGroupId", errorGroupId);
        if (report != null) {
            o.put("title", report.path("title").asText(null));
            o.put("confidence", report.path("confidence").asText(null));
        }
        if (full) {
            o.set("steps", steps.deepCopy());
            o.set("charts", JsonUtil.M.valueToTree(charts));
            o.set("code", JsonUtil.M.valueToTree(code.values()));
            o.set("knowledge", JsonUtil.M.valueToTree(knowledge.values()));
            o.set("actions", JsonUtil.M.valueToTree(actions));
            o.set("report", report);
            o.set("chat", chat.deepCopy());
        }
        return o;
    }

    /** Restores the mutable state saved by toJson(true). */
    public synchronized void restore(JsonNode d) {
        if (d == null || d.isNull()) return;
        status = Status.valueOf(d.path("status").asText("QUEUED"));
        mode = d.path("mode").asText(null);
        model = d.path("model").asText(null);
        failure = d.path("failure").asText(null);
        finishedAt = d.hasNonNull("finishedAt") ? Instant.parse(d.path("finishedAt").asText()) : null;
        report = d.path("report") instanceof ObjectNode r ? r : null;
        d.path("steps").forEach(steps::add);
        d.path("charts").forEach(c -> charts.add((ObjectNode) c));
        int i = 0;
        for (JsonNode c : d.path("code")) code.put(String.valueOf(i++), c);
        d.path("knowledge").forEach(k -> knowledge.put(k.path("docId").asText(), k));
        d.path("actions").forEach(a -> actions.add(a.asText()));
        d.path("chat").forEach(chat::add);
    }
}
