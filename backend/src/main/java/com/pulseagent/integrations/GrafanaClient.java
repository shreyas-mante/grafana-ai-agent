package com.pulseagent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;

/** Read-only Grafana HTTP API: dashboard search and panel queries (use a Viewer service account). */
public class GrafanaClient extends ObservabilityClient {

    public GrafanaClient(String url, String token, boolean allowPrivate) {
        super("Grafana", url, null, token, allowPrivate);
    }

    public ObjectNode search(String query) {
        JsonNode res = get("/api/search?query={q}&type=dash-db&limit=20", query == null ? "" : query);
        ObjectNode out = JsonUtil.obj();
        ArrayNode arr = out.putArray("dashboards");
        for (JsonNode d : res) {
            arr.addObject().put("uid", d.path("uid").asText()).put("title", d.path("title").asText())
                    .put("folder", d.path("folderTitle").asText("")).put("url", baseUrl + d.path("url").asText());
        }
        return out;
    }

    public ObjectNode dashboard(String uid) {
        JsonNode res = get("/api/dashboards/uid/{uid}", uid);
        JsonNode dash = res.path("dashboard");
        ObjectNode out = JsonUtil.obj();
        out.put("uid", uid).put("title", dash.path("title").asText()).put("url", baseUrl + res.path("meta").path("url").asText());
        ArrayNode panels = out.putArray("panels");
        collect(dash.path("panels"), panels);
        return out;
    }

    private static void collect(JsonNode list, ArrayNode out) {
        for (JsonNode p : list) {
            if (out.size() >= 40) return;
            if ("row".equals(p.path("type").asText())) {
                collect(p.path("panels"), out);
                continue;
            }
            ObjectNode o = out.addObject();
            o.put("title", p.path("title").asText()).put("type", p.path("type").asText());
            ArrayNode qs = o.putArray("queries");
            for (JsonNode t : p.path("targets")) {
                String expr = t.path("expr").asText(t.path("query").asText(""));
                if (!expr.isEmpty()) qs.add(expr.length() > 400 ? expr.substring(0, 400) : expr);
            }
        }
    }
}
