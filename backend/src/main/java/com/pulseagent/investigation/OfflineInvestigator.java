package com.pulseagent.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.tools.ToolCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic investigator used when no AI key is configured. It runs the same tools in a
 * fixed order and builds a rule-based report, so the product still works for free and offline.
 */
@Component
public class OfflineInvestigator {

    private record Probe(String title, String filter, String query) {}

    private final ToolCatalog tools;

    public OfflineInvestigator(ToolCatalog tools) {
        this.tools = tools;
    }

    public void investigate(Investigation inv) {
        String svc = inv.service;
        String sel = svc == null ? "" : "{job=~\"(?i).*" + svc.replaceAll("[^A-Za-z0-9_-]", "") + ".*\"}";
        String err5xx = svc == null ? "{status=~\"5..\"}" : "{job=~\"(?i).*" + svc.replaceAll("[^A-Za-z0-9_-]", "") + ".*\",status=~\"5..\"}";

        ArrayNode evidence = JsonUtil.arr();
        ArrayNode unknowns = JsonUtil.arr();

        JsonNode errors = run(inv, "get_recent_errors", obj("service", svc));
        JsonNode topError = errors.path("errors").path(0);
        JsonNode code = null;
        if (!topError.isMissingNode()) {
            String id = inv.errorGroupId != null ? inv.errorGroupId : topError.path("errorId").asText();
            JsonNode codeRes = run(inv, "get_code_context", obj("error_id", id));
            if (codeRes.has("error")) {
                unknowns.add("Source code: " + codeRes.path("error").asText());
            } else {
                // Prefer the caller frame: the throw site is often a library-like helper.
                JsonNode locs = codeRes.path("locations");
                code = locs.size() > 1 ? locs.get(1) : locs.get(0);
            }
        }

        List<Probe> probes = List.of(
                new Probe("Request rate (req/s)", "http_server_requests", "sum(rate(http_server_requests_seconds_count" + sel + "[1m]))"),
                new Probe("5xx error rate (%)", "http_server_requests", "100 * sum(rate(http_server_requests_seconds_count" + err5xx + "[1m])) / clamp_min(sum(rate(http_server_requests_seconds_count" + sel + "[1m])), 1e-9)"),
                new Probe("p95 latency (s)", "http_server_requests", "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket" + sel + "[1m])))"),
                new Probe("Connection pool usage (%)", "pool", "100 * max(demo_db_pool_active" + sel + ") / clamp_min(max(demo_db_pool_max" + sel + "), 1)"));
        for (Probe p : probes) {
            ObjectNode args = obj("query", p.query());
            args.put("lookback_minutes", inv.lookbackMinutes);
            args.put("title", p.title());
            JsonNode r = run(inv, "query_prometheus", args);
            JsonNode s = r.path("series").path(0);
            if (r.has("error") || s.isMissingNode()) {
                unknowns.add(p.title() + ": no data");
                continue;
            }
            evidence.addObject().put("signal", p.title())
                    .put("before", s.path("baselineMean").asText())
                    .put("after", s.path("recentMean").asText())
                    .put("source", "query_prometheus");
        }

        String searchText = topError.isMissingNode() ? inv.question : topError.path("exception").asText() + " " + topError.path("message").asText();
        JsonNode kb = run(inv, "search_knowledge_base", obj("query", searchText));
        run(inv, "get_recent_commits", obj("hours", null));

        ObjectNode report = JsonUtil.obj();
        JsonNode best = kb.path("results").path(0);
        boolean strongKb = best.path("score").asDouble() >= 0.25;
        String cause;
        if (!topError.isMissingNode()) {
            cause = simpleName(topError.path("exception").asText()) + " in " + topError.path("topAppFrame").asText("application code")
                    + (strongKb ? ", matching the known pattern \"" + best.path("title").asText() + "\"" : "");
        } else {
            cause = "No application errors were reported; see the metric evidence for anomalies.";
        }
        report.put("title", topError.isMissingNode() ? "Investigation: " + JsonUtil.truncate(inv.question, 80)
                : simpleName(topError.path("exception").asText()) + " on " + topError.path("endpoint").asText(topError.path("service").asText()));
        report.put("summary", "Offline analysis (no AI key configured). " + (topError.isMissingNode() ? "" :
                topError.path("count").asLong() + " occurrences of " + simpleName(topError.path("exception").asText()) + ": "
                        + JsonUtil.truncate(topError.path("message").asText(), 200) + "."));
        report.put("likelyCause", cause);
        report.put("confidence", !topError.isMissingNode() && strongKb ? "medium" : "low");
        report.set("evidence", evidence);
        if (code != null) {
            ObjectNode cf = report.putObject("codeFinding");
            String expl = "The failure passes through this line: " + code.path("failingLineText").asText().trim() + ".";
            JsonNode changed = code.path("linesChangedByNewestCommit");
            if (changed.size() > 0 && changed.size() <= 6) {
                int failing = code.path("line").asInt();
                JsonNode nearest = changed.get(0);
                for (JsonNode ch : changed) {
                    boolean isComment = ch.path("text").asText().startsWith("/") || ch.path("text").asText().startsWith("*");
                    boolean nearestIsComment = nearest.path("text").asText().startsWith("/") || nearest.path("text").asText().startsWith("*");
                    if ((nearestIsComment && !isComment) || (isComment == nearestIsComment
                            && Math.abs(ch.path("line").asInt() - failing) < Math.abs(nearest.path("line").asInt() - failing))) {
                        nearest = ch;
                    }
                }
                expl += " The most recent commit near it (" + code.path("newestCommitNearFailure").asText() + ") changed line "
                        + nearest.path("line").asInt() + ": " + nearest.path("text").asText() + ". Review that change first.";
            }
            cf.put("file", code.path("file").asText()).put("line", code.path("line").asInt()).put("explanation", expl);
        }
        if (strongKb) {
            String fix = extractSection(best.path("excerpt").asText(), "fix");
            report.putObject("suggestedFix").put("explanation", fix != null ? fix
                    : "Follow the runbook \"" + best.path("title").asText() + "\".").put("diff", "");
            report.putArray("knowledgeUsed").add(best.path("title").asText());
        }
        ArrayNode next = report.putArray("nextSteps");
        next.add("Add GEMINI_API_KEY to get an AI-written root cause analysis and code fix.");
        if (code != null) next.add("Review " + code.path("file").asText() + " line " + code.path("line").asInt() + " and the commit that last changed it.");
        report.set("unknowns", unknowns);
        inv.complete(report);
    }

    public String chat(Investigation inv, String message) {
        JsonNode r = tools.execute("search_knowledge_base", obj("query", message), inv);
        StringBuilder sb = new StringBuilder("Offline mode (no AI key configured). Closest matches in your knowledge base:\n");
        for (JsonNode h : r.path("results")) {
            sb.append("- ").append(h.path("title").asText()).append(" (").append(h.path("type").asText())
                    .append(", score ").append(h.path("score").asText()).append(")\n");
        }
        return sb.toString();
    }

    private JsonNode run(Investigation inv, String tool, ObjectNode args) {
        int idx = inv.addStep(tool, args);
        long t0 = System.currentTimeMillis();
        JsonNode r = tools.execute(tool, args, inv);
        inv.finishStep(idx, r.has("error") ? "failed" : "done", AgentRunner.describe(tool, r), System.currentTimeMillis() - t0);
        return r;
    }

    private static ObjectNode obj(String k, String v) {
        ObjectNode o = JsonUtil.obj();
        if (v != null) o.put(k, v);
        return o;
    }

    private static String simpleName(String cls) {
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    private static String extractSection(String text, String keyword) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String l : lines) {
            if (l.startsWith("#")) {
                if (in) break;
                in = l.toLowerCase(Locale.ROOT).contains(keyword);
                continue;
            }
            if (in) sb.append(l).append('\n');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
