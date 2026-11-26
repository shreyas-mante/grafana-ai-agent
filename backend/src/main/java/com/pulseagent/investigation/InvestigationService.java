package com.pulseagent.investigation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.llm.GeminiClient;
import com.pulseagent.llm.LlmException;
import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.security.Redactor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Creates investigations, runs them on a bounded worker pool, and keeps a bounded history. */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_STORED = 200;

    private final Map<String, Investigation> store = new ConcurrentHashMap<>();
    private final Map<String, Instant> recentAlerts = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(25), Thread.ofVirtual().name("investigation-", 0).factory());

    private final GeminiClient llm;
    private final AgentRunner agent;
    private final OfflineInvestigator offline;
    private final KnowledgeBase kb;

    public InvestigationService(GeminiClient llm, AgentRunner agent, OfflineInvestigator offline, KnowledgeBase kb) {
        this.llm = llm;
        this.agent = agent;
        this.offline = offline;
        this.kb = kb;
    }

    public Investigation start(String question, String service, Integer lookback, String errorGroupId, String trigger) {
        Investigation inv = new Investigation(Redactor.redact(question), service,
                lookback == null ? 30 : lookback, errorGroupId, trigger);
        if (store.size() >= MAX_STORED) {
            store.values().stream().min(Comparator.comparing(i -> i.createdAt)).ifPresent(i -> store.remove(i.id));
        }
        store.put(inv.id, inv);
        try {
            workers.submit(() -> run(inv));
        } catch (RejectedExecutionException e) {
            store.remove(inv.id);
            throw new IllegalStateException("Too many investigations are running. Try again shortly.");
        }
        return inv;
    }

    /** Starts an alert-driven investigation unless the same alert fired in the last 10 minutes. */
    public Optional<Investigation> startFromAlert(String alertName, String service, String summary) {
        String key = alertName + "|" + service;
        Instant now = Instant.now();
        Instant last = recentAlerts.get(key);
        if (last != null && last.isAfter(now.minusSeconds(600))) return Optional.empty();
        recentAlerts.put(key, now);
        recentAlerts.entrySet().removeIf(e -> e.getValue().isBefore(now.minusSeconds(3600)));
        String q = "Alert \"" + alertName + "\" is firing" + (service != null ? " for " + service : "")
                + (summary != null && !summary.isBlank() ? ": " + summary : "") + ". Find the root cause.";
        return Optional.of(start(q, service, 30, null, "alert"));
    }

    private void run(Investigation inv) {
        boolean ai = llm.available();
        inv.start(ai ? "ai" : "offline");
        try {
            if (ai) agent.investigate(inv);
            else offline.investigate(inv);
            saveAsIncident(inv);
        } catch (LlmException e) {
            inv.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("Investigation {} failed: {}", inv.id, e.getClass().getSimpleName());
            inv.fail("The investigation failed unexpectedly.");
        }
    }

    public String chat(Investigation inv, String message) {
        String clean = Redactor.redact(message);
        String answer = llm.available() ? agent.chat(inv, clean) : offline.chat(inv, clean);
        inv.addChat("user", clean);
        inv.addChat("assistant", answer);
        return answer;
    }

    /** Every completed AI investigation becomes a searchable incident, so the knowledge base grows. */
    private void saveAsIncident(Investigation inv) {
        ObjectNode r = inv.report();
        if (r == null || !"ai".equals(inv.toJson(false).path("mode").asText()) || !r.hasNonNull("likelyCause")) return;
        StringBuilder md = new StringBuilder();
        md.append("Date: ").append(LocalDate.now()).append("\n");
        if (inv.service != null) md.append("Service: ").append(inv.service).append("\n");
        md.append("Question: ").append(inv.question).append("\n\n");
        md.append("## Symptoms\n").append(r.path("summary").asText()).append("\n\n");
        md.append("## Root cause\n").append(r.path("likelyCause").asText()).append("\n\n");
        if (r.path("codeFinding").has("file")) {
            md.append("## Code\n").append(r.path("codeFinding").path("file").asText()).append(":")
                    .append(r.path("codeFinding").path("line").asText()).append(" ")
                    .append(r.path("codeFinding").path("explanation").asText()).append("\n\n");
        }
        if (r.path("suggestedFix").has("explanation")) {
            md.append("## Fix\n").append(r.path("suggestedFix").path("explanation").asText()).append("\n");
        }
        try {
            kb.add(r.path("title").asText("Incident " + inv.id), "incident", md.toString(), "auto:" + inv.id);
        } catch (Exception e) {
            log.warn("Could not save incident to knowledge base: {}", e.getMessage());
        }
    }

    public Optional<Investigation> get(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Investigation> list() {
        return store.values().stream().sorted(Comparator.comparing((Investigation i) -> i.createdAt).reversed()).toList();
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
