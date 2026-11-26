package com.pulseagent.investigation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.llm.Llm;
import com.pulseagent.llm.LlmException;
import com.pulseagent.notify.NotificationService;
import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.security.Redactor;
import com.pulseagent.tenant.Crypto;
import com.pulseagent.tenant.EnvFactory;
import com.pulseagent.tenant.WorkspaceEnv;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Investigations run from a durable, database-backed queue: creation only inserts a QUEUED row;
 * a worker claims rows, runs them with bounded concurrency, and saves state after every step.
 * Jobs interrupted by a crash are retried on startup.
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final int MAX_CONCURRENT = 3;
    private static final int MAX_QUEUED_PER_WORKSPACE_PER_HOUR = 120;

    private final Map<String, Investigation> running = new ConcurrentHashMap<>();
    private final Map<String, Instant> recentAlerts = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    private volatile boolean ready;

    private final InvestigationRepo repo;
    private final EnvFactory envs;
    private final AgentRunner agent;
    private final OfflineInvestigator offline;
    private final KnowledgeBase kb;
    private final NotificationService notify;

    public InvestigationService(InvestigationRepo repo, EnvFactory envs, AgentRunner agent, OfflineInvestigator offline,
                                KnowledgeBase kb, NotificationService notify) {
        this.repo = repo;
        this.envs = envs;
        this.agent = agent;
        this.offline = offline;
        this.kb = kb;
        this.notify = notify;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int n = repo.recover();
        if (n > 0) log.info("Recovered {} interrupted investigations", n);
        ready = true;
    }

    public Investigation start(String ws, String question, String service, Integer lookback, String errorGroupId, String trigger) {
        if (repo.countRecent(ws, trigger, 60) >= MAX_QUEUED_PER_WORKSPACE_PER_HOUR) {
            throw new IllegalStateException("This workspace started too many investigations in the last hour. Try again later.");
        }
        String id = "inv_" + java.util.HexFormat.of().formatHex(Crypto.randomBytes(8));
        Investigation inv = new Investigation(id, ws, Redactor.redact(question), service, lookback == null ? 30 : lookback,
                errorGroupId, trigger, Instant.now());
        repo.insert(inv);
        return inv;
    }

    /** Starts an alert-driven investigation unless the same alert fired in the last 10 minutes. */
    public Optional<Investigation> startFromAlert(String ws, String alertName, String service, String summary) {
        String key = ws + "|" + alertName + "|" + service;
        Instant now = Instant.now();
        Instant last = recentAlerts.get(key);
        if (last != null && last.isAfter(now.minusSeconds(600))) return Optional.empty();
        recentAlerts.put(key, now);
        recentAlerts.entrySet().removeIf(e -> e.getValue().isBefore(now.minusSeconds(3600)));
        String q = "Alert \"" + alertName + "\" is firing" + (service != null ? " for " + service : "")
                + (summary != null && !summary.isBlank() ? ": " + summary : "") + ". Find the root cause.";
        return Optional.of(start(ws, q, service, 30, null, "alert"));
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        if (!ready) return;
        int free = slots.availablePermits();
        if (free <= 0) return;
        for (String[] job : repo.claim(free)) {
            if (!slots.tryAcquire()) break;
            workers.submit(() -> {
                try {
                    repo.load(job[1], job[0]).ifPresent(this::run);
                } finally {
                    slots.release();
                }
            });
        }
    }

    @Scheduled(cron = "0 17 3 * * *")
    void prune() {
        int n = repo.pruneOld(30);
        if (n > 0) log.info("Pruned {} investigations older than 30 days", n);
    }

    private void run(Investigation inv) {
        running.put(inv.id, inv);
        try {
            WorkspaceEnv env = envs.forWorkspace(inv.workspaceId);
            Optional<Llm.Provider> provider = env.llm();
            inv.start(provider.isPresent() ? "ai" : "offline", provider.map(p -> p.name() + "/" + p.model()).orElse(null));
            repo.save(inv);
            Runnable checkpoint = () -> repo.save(inv);
            if (provider.isPresent()) agent.investigate(inv, env, provider.get(), checkpoint);
            else offline.investigate(inv, env, checkpoint);
            saveAsIncident(inv);
        } catch (LlmException e) {
            inv.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("Investigation {} failed: {}", inv.id, e.getClass().getSimpleName());
            inv.fail("The investigation failed unexpectedly.");
        } finally {
            repo.save(inv);
            running.remove(inv.id);
        }
        try {
            notify.investigationFinished(inv);
        } catch (Exception e) {
            log.warn("Notification failed for {}: {}", inv.id, e.getClass().getSimpleName());
        }
    }

    public String chat(Investigation inv, String message) {
        String clean = Redactor.redact(message);
        WorkspaceEnv env = envs.forWorkspace(inv.workspaceId);
        String answer = env.llm().isPresent() ? agent.chat(inv, env, env.llm().get(), clean) : offline.chat(inv, env, clean);
        inv.addChat("user", clean);
        inv.addChat("assistant", answer);
        repo.save(inv);
        return answer;
    }

    /** Every completed AI investigation becomes a searchable incident, so the knowledge base grows. */
    private void saveAsIncident(Investigation inv) {
        ObjectNode r = inv.report();
        if (r == null || !"ai".equals(inv.mode()) || !r.hasNonNull("likelyCause")) return;
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
        if (r.path("suggestedFix").has("explanation")) md.append("## Fix\n").append(r.path("suggestedFix").path("explanation").asText()).append("\n");
        try {
            kb.add(inv.workspaceId, r.path("title").asText("Incident " + inv.id), "incident", md.toString(), "auto:" + inv.id);
        } catch (Exception e) {
            log.warn("Could not save incident to knowledge base: {}", e.getMessage());
        }
    }

    /** Live state while running (fresher than the last checkpoint), otherwise the stored copy. */
    public Optional<Investigation> get(String ws, String id) {
        Investigation live = running.get(id);
        if (live != null && live.workspaceId.equals(ws)) return Optional.of(live);
        return repo.load(ws, id);
    }

    public List<Map<String, Object>> list(String ws) {
        return repo.list(ws, 100);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
