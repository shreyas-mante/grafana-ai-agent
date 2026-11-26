package com.pulseagent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only Tempo trace search and trace summaries (slowest spans, errors). */
public class TempoClient extends ObservabilityClient {

    public TempoClient(String url, String username, String token, boolean allowPrivate) {
        super("Tempo", url, username, token, allowPrivate);
    }

    public int tags() {
        JsonNode r = get("/api/search/tags");
        return r.path("tagNames").size() + r.path("scopes").size();
    }

    public ObjectNode search(String service, int lookbackMinutes, int minDurationMs, int limit) {
        long end = Instant.now().getEpochSecond();
        long start = end - lookbackMinutes * 60L;
        String q = "{ resource.service.name = \"" + service + "\"" + (minDurationMs > 0 ? " && duration > " + minDurationMs + "ms" : "") + " }";
        JsonNode res = get("/api/search?q={q}&start={s}&end={e}&limit={l}", q, start, end, limit);
        ObjectNode out = JsonUtil.obj();
        out.put("traceql", q);
        ArrayNode traces = out.putArray("traces");
        for (JsonNode t : res.path("traces")) {
            traces.addObject().put("traceId", t.path("traceID").asText())
                    .put("rootService", t.path("rootServiceName").asText())
                    .put("rootName", t.path("rootTraceName").asText())
                    .put("durationMs", t.path("durationMs").asLong())
                    .put("start", Instant.ofEpochMilli(t.path("startTimeUnixNano").asLong() / 1_000_000L).toString());
        }
        if (traces.isEmpty()) out.put("note", "No traces matched.");
        return out;
    }

    public ObjectNode trace(String traceId) {
        JsonNode res = get("/api/traces/{id}", traceId);
        record Span(String service, String name, double ms, boolean error) {}
        List<Span> spans = new ArrayList<>();
        JsonNode batches = res.has("batches") ? res.path("batches") : res.path("resourceSpans");
        for (JsonNode b : batches) {
            String service = "unknown";
            for (JsonNode a : b.path("resource").path("attributes")) {
                if ("service.name".equals(a.path("key").asText())) service = a.path("value").path("stringValue").asText();
            }
            JsonNode scopes = b.has("scopeSpans") ? b.path("scopeSpans") : b.path("instrumentationLibrarySpans");
            for (JsonNode s : scopes) {
                for (JsonNode sp : s.path("spans")) {
                    double ms = (sp.path("endTimeUnixNano").asDouble() - sp.path("startTimeUnixNano").asDouble()) / 1_000_000.0;
                    String code = sp.path("status").path("code").asText("");
                    spans.add(new Span(service, sp.path("name").asText(), Math.round(ms * 10) / 10.0,
                            code.equals("STATUS_CODE_ERROR") || code.equals("2")));
                }
            }
        }
        spans.sort(Comparator.comparingDouble(Span::ms).reversed());
        ObjectNode out = JsonUtil.obj();
        out.put("traceId", traceId).put("spanCount", spans.size());
        ArrayNode slow = out.putArray("slowestSpans");
        spans.stream().limit(12).forEach(s -> slow.addObject().put("service", s.service()).put("name", s.name()).put("durationMs", s.ms()).put("error", s.error()));
        ArrayNode errs = out.putArray("errorSpans");
        spans.stream().filter(Span::error).limit(10).forEach(s -> errs.addObject().put("service", s.service()).put("name", s.name()).put("durationMs", s.ms()));
        return out;
    }
}
