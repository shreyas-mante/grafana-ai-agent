package com.pulseagent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.tools.ToolException;

/** Read-only Prometheus HTTP API client (works with Grafana Cloud's /api/prom endpoint). */
public class PrometheusClient extends ObservabilityClient {

    public PrometheusClient(String url, String username, String token, boolean allowPrivate) {
        super("Prometheus", url, username, token, allowPrivate);
    }

    public JsonNode queryRange(String query, long start, long end, int step) {
        return data(get("/api/v1/query_range?query={q}&start={s}&end={e}&step={st}", query, start, end, step));
    }

    public JsonNode metricNames() {
        return data(get("/api/v1/label/__name__/values"));
    }

    public JsonNode alerts() {
        return data(get("/api/v1/alerts"));
    }

    private static JsonNode data(JsonNode res) {
        if (!"success".equals(res.path("status").asText())) throw new ToolException("Prometheus returned an unsuccessful response");
        return res.path("data");
    }
}
