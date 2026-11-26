package com.pulseagent.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends exception reports asynchronously. Never blocks or throws into the host application:
 * a bounded queue drops reports under pressure, and a simple rate limit caps send volume.
 */
public class ErrorReporter {

    private static final Logger log = LoggerFactory.getLogger(ErrorReporter.class);
    private static final int MAX_PER_SECOND = 20;

    private final PulseAgentProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "pulseagent-reporter");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());
    private final AtomicLong windowSecond = new AtomicLong();
    private final AtomicLong windowCount = new AtomicLong();
    private final URI uri;

    public ErrorReporter(PulseAgentProperties props) {
        this.props = props;
        URI u = URI.create(props.getEndpoint().replaceAll("/+$", "") + "/api/v1/errors");
        if (!"https".equals(u.getScheme()) && !isLocal(u)) {
            throw new IllegalStateException("pulseagent.endpoint must use https (http is allowed only for localhost)");
        }
        this.uri = u;
    }

    public void report(Throwable t, String httpMethod, String path) {
        if (!allow()) return;
        try {
            executor.execute(() -> send(t, httpMethod, path));
        } catch (Exception ignored) {
            // never affect the host application
        }
    }

    private void send(Throwable t, String httpMethod, String path) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", props.getService());
            body.put("environment", props.getEnvironment());
            if (props.getCommitSha() != null && props.getCommitSha().matches("[0-9a-f]{7,40}")) body.put("commitSha", props.getCommitSha());
            body.put("exceptionClass", t.getClass().getName());
            body.put("message", truncate(String.valueOf(t.getMessage()), 2000));
            body.put("httpMethod", httpMethod);
            body.put("path", truncate(path, 300));
            List<Map<String, Object>> frames = new ArrayList<>();
            for (StackTraceElement e : t.getStackTrace()) {
                if (frames.size() >= 60) break;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("className", e.getClassName());
                f.put("methodName", e.getMethodName().replaceAll("[^\\w$<>-]", ""));
                f.put("fileName", e.getFileName() == null ? "" : e.getFileName());
                f.put("lineNumber", e.getLineNumber());
                frames.add(f);
            }
            body.put("frames", frames);
            List<Map<String, Object>> causes = new ArrayList<>();
            Throwable c = t.getCause();
            while (c != null && c != t && causes.size() < 5) {
                causes.add(Map.of("exceptionClass", c.getClass().getName(), "message", truncate(String.valueOf(c.getMessage()), 1000)));
                c = c.getCause();
            }
            body.put("causes", causes);

            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", props.getIngestKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 300) log.debug("PulseAgent rejected error report: HTTP {}", res.statusCode());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("PulseAgent error report failed: {}", e.getClass().getSimpleName());
        }
    }

    private boolean allow() {
        long sec = System.currentTimeMillis() / 1000;
        if (windowSecond.getAndSet(sec) != sec) windowCount.set(0);
        return windowCount.incrementAndGet() <= MAX_PER_SECOND;
    }

    private static boolean isLocal(URI u) {
        return "http".equals(u.getScheme()) && ("localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
