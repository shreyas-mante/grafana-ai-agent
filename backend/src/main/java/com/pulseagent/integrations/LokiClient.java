package com.pulseagent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.security.Redactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only Loki log search. Lines are capped, truncated and redacted before anyone sees them. */
public class LokiClient extends ObservabilityClient {

    public LokiClient(String url, String username, String token, boolean allowPrivate) {
        super("Loki", url, username, token, allowPrivate);
    }

    public int labels() {
        return get("/loki/api/v1/labels").path("data").size();
    }

    public ObjectNode search(String logql, int lookbackMinutes, int limit) {
        long endNs = Instant.now().toEpochMilli() * 1_000_000L;
        long startNs = endNs - lookbackMinutes * 60L * 1_000_000_000L;
        JsonNode res = get("/loki/api/v1/query_range?query={q}&start={s}&end={e}&limit={l}&direction=backward",
                logql, startNs, endNs, limit);
        record Line(long ts, String stream, String text) {}
        List<Line> lines = new ArrayList<>();
        for (JsonNode stream : res.path("data").path("result")) {
            String labels = stream.path("stream").toString();
            for (JsonNode v : stream.path("values")) {
                String text = v.path(1).asText();
                lines.add(new Line(v.path(0).asLong() / 1_000_000L, labels.length() > 200 ? labels.substring(0, 200) : labels,
                        Redactor.redact(text.length() > 400 ? text.substring(0, 400) + "…" : text)));
            }
        }
        lines.sort(Comparator.comparingLong(Line::ts).reversed());
        ObjectNode out = JsonUtil.obj();
        out.put("query", logql).put("lookbackMinutes", lookbackMinutes).put("totalLines", lines.size());
        ArrayNode arr = out.putArray("lines");
        for (Line l : lines.subList(0, Math.min(lines.size(), limit))) {
            arr.addObject().put("time", Instant.ofEpochMilli(l.ts()).toString()).put("stream", l.stream()).put("line", l.text());
        }
        if (lines.isEmpty()) out.put("note", "No log lines matched. Check the stream selector labels.");
        return out;
    }
}
