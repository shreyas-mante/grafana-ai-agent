package com.pulseagent.errors;

import com.pulseagent.config.AppProperties;
import com.pulseagent.security.ApiKeyService;
import com.pulseagent.security.Redactor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory, bounded store of error groups reported by applications. */
@Service
public class ErrorStore {

    private static final int MAX_GROUPS = 500;
    private final Map<String, ErrorGroup> groups = new ConcurrentHashMap<>();
    private final List<String> appPackages;

    public ErrorStore(AppProperties props) {
        this.appPackages = props.code().appPackages() == null ? List.of() : props.code().appPackages();
    }

    public ErrorGroup record(ErrorEventRequest e) {
        ErrorEventRequest.Frame top = topAppFrame(e.frames()).orElse(e.frames().isEmpty() ? null : e.frames().get(0));
        String fingerprint = e.service() + "|" + e.exceptionClass() + "|"
                + (top == null ? "" : top.className() + "." + top.methodName() + ":" + top.lineNumber());
        String id = "err_" + HexFormat.of().formatHex(ApiKeyService.sha256(fingerprint)).substring(0, 16);

        if (groups.size() >= MAX_GROUPS && !groups.containsKey(id)) evictOldest();
        ErrorGroup g = groups.computeIfAbsent(id, k -> new ErrorGroup(k, e.service(), e.exceptionClass()));
        g.count.incrementAndGet();
        g.lastSeen = Instant.now();
        g.environment = e.environment();
        g.commitSha = e.commitSha() == null || e.commitSha().isBlank() ? g.commitSha : e.commitSha();
        g.message = Redactor.redact(e.message());
        g.httpMethod = e.httpMethod();
        g.path = Redactor.redact(e.path());
        g.frames = List.copyOf(e.frames());
        g.causes = e.causes() == null ? List.of() : e.causes().stream()
                .map(c -> new ErrorEventRequest.Cause(c.exceptionClass(), Redactor.redact(c.message()))).toList();
        return g;
    }

    public Optional<ErrorGroup> get(String id) {
        return Optional.ofNullable(groups.get(id));
    }

    public List<ErrorGroup> recent(String service, int limit) {
        return groups.values().stream()
                .filter(g -> service == null || service.isBlank() || g.service.equalsIgnoreCase(service))
                .sorted(Comparator.comparing((ErrorGroup g) -> g.lastSeen).reversed())
                .limit(limit)
                .toList();
    }

    /** First stack frame that belongs to the customer's own code (by configured package prefix). */
    public Optional<ErrorEventRequest.Frame> topAppFrame(List<ErrorEventRequest.Frame> frames) {
        if (frames == null) return Optional.empty();
        return frames.stream()
                .filter(f -> f.lineNumber() > 0)
                .filter(f -> appPackages.isEmpty() || appPackages.stream().anyMatch(p -> f.className().startsWith(p.trim())))
                .findFirst();
    }

    /** Up to n application frames from distinct files, innermost first (where it threw, then its callers). */
    public List<ErrorEventRequest.Frame> appFrames(List<ErrorEventRequest.Frame> frames, int n) {
        if (frames == null) return List.of();
        java.util.Set<String> files = new java.util.HashSet<>();
        return frames.stream()
                .filter(f -> f.lineNumber() > 0)
                .filter(f -> appPackages.isEmpty() || appPackages.stream().anyMatch(p -> f.className().startsWith(p.trim())))
                .filter(f -> files.add(f.className().replaceAll("\\$.*", "")))
                .limit(n)
                .toList();
    }

    private void evictOldest() {
        groups.values().stream().min(Comparator.comparing(g -> g.lastSeen)).ifPresent(g -> groups.remove(g.id));
    }
}
