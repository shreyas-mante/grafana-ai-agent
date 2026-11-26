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
import java.util.UUID;

/** One investigation: its inputs, live steps, verified artefacts, final report and chat. */
public class Investigation implements ToolContext {

    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }

    public record Step(int index, String tool, JsonNode args, String status, String summary, long durationMs) {}

    public record ChatMessage(String role, String text, String at) {}

    public final String id = "inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    public final String question;
    public final String service;
    public final int lookbackMinutes;
    public final String errorGroupId;
    public final String trigger;
    public final Instant createdAt = Instant.now();

    private Status status = Status.QUEUED;
    private String mode;
    private String failure;
    private Instant finishedAt;
    private ObjectNode report;
    private final List<Step> steps = new ArrayList<>();
    private final List<ObjectNode> charts = new ArrayList<>();
    private final Map<String, CodeContext> code = new LinkedHashMap<>();
    private final Map<String, KnowledgeBase.Hit> knowledge = new LinkedHashMap<>();
    private final List<ChatMessage> chat = new ArrayList<>();

    public Investigation(String question, String service, int lookbackMinutes, String errorGroupId, String trigger) {
        this.question = question;
        this.service = service;
        this.lookbackMinutes = lookbackMinutes;
        this.errorGroupId = errorGroupId;
        this.trigger = trigger;
    }

    public synchronized void start(String mode) {
        this.status = Status.RUNNING;
        this.mode = mode;
    }

    public synchronized int addStep(String tool, JsonNode args) {
        steps.add(new Step(steps.size(), tool, args, "running", null, 0));
        return steps.size() - 1;
    }

    public synchronized void finishStep(int i, String status, String summary, long ms) {
        Step s = steps.get(i);
        steps.set(i, new Step(s.index(), s.tool(), s.args(), status, summary, ms));
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

    public synchronized ObjectNode report() { return report; }

    public synchronized void addChat(String role, String text) {
        chat.add(new ChatMessage(role, text, Instant.now().toString()));
        while (chat.size() > 40) chat.remove(0);
    }

    public synchronized List<ChatMessage> chatHistory() { return List.copyOf(chat); }

    public synchronized List<CodeContext> codeContexts() { return List.copyOf(code.values()); }

    public synchronized List<KnowledgeBase.Hit> knowledgeHits() { return List.copyOf(knowledge.values()); }

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
    public synchronized void attachCode(String errorGroupId, CodeContext ctx) {
        code.put(errorGroupId, ctx);
    }

    @Override
    public synchronized void attachKnowledge(List<KnowledgeBase.Hit> hits) {
        for (KnowledgeBase.Hit h : hits) {
            KnowledgeBase.Hit old = knowledge.get(h.docId());
            if (old == null || old.score() < h.score()) knowledge.put(h.docId(), h);
        }
    }

    /** Compact evidence bundle used as context for follow-up chat. */
    public synchronized String contextForChat() {
        ObjectNode o = JsonUtil.obj();
        o.put("question", question).put("service", service).put("lookbackMinutes", lookbackMinutes);
        if (report != null) o.set("report", report);
        o.set("code", JsonUtil.M.valueToTree(code.values()));
        ArrayNode k = o.putArray("knowledge");
        knowledge.values().forEach(h -> k.addObject().put("title", h.title()).put("type", h.type()).put("excerpt", JsonUtil.truncate(h.excerpt(), 500)));
        ArrayNode c = o.putArray("charts");
        charts.forEach(ch -> c.addObject().put("title", ch.path("title").asText()).set("summary", ch.path("summary")));
        return JsonUtil.truncate(JsonUtil.write(o), 20_000);
    }

    public synchronized ObjectNode toJson(boolean full) {
        ObjectNode o = JsonUtil.obj();
        o.put("id", id).put("question", question).put("service", service).put("lookbackMinutes", lookbackMinutes)
                .put("trigger", trigger).put("status", status.name()).put("mode", mode)
                .put("createdAt", createdAt.toString())
                .put("finishedAt", finishedAt == null ? null : finishedAt.toString())
                .put("durationSeconds", finishedAt == null ? null : (finishedAt.toEpochMilli() - createdAt.toEpochMilli()) / 1000.0)
                .put("stepCount", steps.size())
                .put("failure", failure);
        if (report != null) {
            o.put("title", report.path("title").asText(null));
            o.put("confidence", report.path("confidence").asText(null));
        }
        if (full) {
            o.set("steps", JsonUtil.M.valueToTree(steps));
            o.set("charts", JsonUtil.M.valueToTree(charts));
            o.set("code", JsonUtil.M.valueToTree(code.values()));
            o.set("knowledge", JsonUtil.M.valueToTree(knowledge.values()));
            o.set("report", report);
            o.set("chat", JsonUtil.M.valueToTree(chat));
        }
        return o;
    }
}
