package com.pulseagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.code.CodeIntelService;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.errors.ErrorGroup;
import com.pulseagent.errors.ErrorStore;
import com.pulseagent.rag.KnowledgeBase;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Allowlist of tools available to the agent. Arguments from the model are untrusted:
 * every value is type-checked, bounded and validated before anything is executed.
 */
@Component
public class ToolCatalog {

    private static final int MAX_OUTPUT_CHARS = 12_000;

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final PrometheusClient prom;
    private final ErrorStore errors;
    private final CodeIntelService code;
    private final KnowledgeBase kb;
    private final AppProperties.Limits limits;

    public ToolCatalog(PrometheusClient prom, ErrorStore errors, CodeIntelService code, KnowledgeBase kb, AppProperties props) {
        this.prom = prom;
        this.errors = errors;
        this.code = code;
        this.kb = kb;
        this.limits = props.limits();
        register();
    }

    public ArrayNode declarations() {
        ArrayNode a = JsonUtil.arr();
        tools.values().forEach(t -> a.add(t.declaration()));
        return a;
    }

    /** Executes an allowlisted tool. Never throws: failures become an {error} result the model can read. */
    public JsonNode execute(String name, JsonNode args, ToolContext ctx) {
        AgentTool tool = tools.get(name);
        if (tool == null) return JsonUtil.obj().put("error", "Unknown tool: " + JsonUtil.truncate(name, 60));
        try {
            JsonNode out = tool.executor().apply(args == null || args.isNull() ? JsonUtil.obj() : args, ctx);
            String s = JsonUtil.write(out);
            if (s.length() > MAX_OUTPUT_CHARS) {
                return JsonUtil.obj().put("truncated", true).put("partial", s.substring(0, MAX_OUTPUT_CHARS));
            }
            return out;
        } catch (ToolException | IllegalArgumentException e) {
            return JsonUtil.obj().put("error", e.getMessage());
        } catch (Exception e) {
            return JsonUtil.obj().put("error", "Tool failed: " + e.getClass().getSimpleName());
        }
    }

    private void register() {
        add("list_metrics", "List Prometheus metric names, optionally filtered by a case-insensitive substring. Use this first to discover real metric names instead of guessing.",
                params(p("filter", "STRING", "Substring to filter metric names, e.g. 'http' or 'pool'", false)),
                (a, ctx) -> {
                    String filter = str(a, "filter", 100, false);
                    JsonNode names = prom.metricNames();
                    ArrayNode out = JsonUtil.arr();
                    int total = 0;
                    for (JsonNode n : names) {
                        String s = n.asText();
                        if (filter == null || s.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                            total++;
                            if (out.size() < 150) out.add(s);
                        }
                    }
                    return JsonUtil.obj().put("matching", total).set("metrics", out);
                });

        add("query_prometheus", "Run a read-only PromQL range query over a recent window and get per-series statistics, including a baseline (earlier part of the window) versus recent comparison.",
                params(p("query", "STRING", "PromQL expression", true),
                        p("lookback_minutes", "INTEGER", "Window size ending now, 5 to 1440. Default 60", false),
                        p("step_seconds", "INTEGER", "Resolution in seconds. Default chosen automatically", false),
                        p("title", "STRING", "Short human-readable label for this query, e.g. 'p95 latency'", false)),
                this::queryPrometheus);

        add("get_firing_alerts", "List currently firing and pending Prometheus alerts with their labels and annotations.",
                params(),
                (a, ctx) -> {
                    ArrayNode out = JsonUtil.arr();
                    for (JsonNode al : prom.alerts().path("alerts")) {
                        if (out.size() >= 30) break;
                        out.addObject().put("state", al.path("state").asText())
                                .put("activeAt", al.path("activeAt").asText())
                                .set("labels", al.path("labels"));
                        ((ObjectNode) out.get(out.size() - 1)).set("annotations", al.path("annotations"));
                    }
                    return JsonUtil.obj().set("alerts", out);
                });

        add("get_recent_errors", "List recent application exceptions reported by the PulseAgent SDK, grouped and counted, with the top application stack frame.",
                params(p("service", "STRING", "Service name to filter by, optional", false)),
                (a, ctx) -> {
                    String service = str(a, "service", 100, false);
                    ArrayNode out = JsonUtil.arr();
                    for (ErrorGroup g : errors.recent(service, 10)) {
                        ObjectNode o = out.addObject();
                        o.put("errorId", g.id).put("service", g.service).put("exception", g.exceptionClass)
                                .put("message", JsonUtil.truncate(g.message, 500)).put("count", g.count.get())
                                .put("firstSeen", g.firstSeen.toString()).put("lastSeen", g.lastSeen.toString())
                                .put("endpoint", (g.httpMethod == null ? "" : g.httpMethod + " ") + (g.path == null ? "" : g.path))
                                .put("commitSha", g.commitSha);
                        errors.topAppFrame(g.frames).ifPresent(f -> o.put("topAppFrame", f.className() + "." + f.methodName() + "(" + f.fileName() + ":" + f.lineNumber() + ")"));
                        if (g.causes != null && !g.causes.isEmpty()) {
                            ArrayNode causes = o.putArray("causes");
                            g.causes.forEach(c -> causes.add(c.exceptionClass() + ": " + JsonUtil.truncate(c.message(), 200)));
                        }
                    }
                    return JsonUtil.obj().put("codeProvider", code.providerName()).set("errors", out);
                });

        add("get_code_context", "For an error group, resolve the top application stack frames (where it was thrown, then the caller) in the source at the deployed commit: file path, exact line, surrounding code, and git blame (author, commit, date, pull request). The root cause is often in the caller, not where the exception was thrown.",
                params(p("error_id", "STRING", "errorId from get_recent_errors", true)),
                (a, ctx) -> {
                    String id = str(a, "error_id", 40, true);
                    if (!id.matches("err_[0-9a-f]{16}")) throw new IllegalArgumentException("Invalid error_id");
                    ErrorGroup g = errors.get(id).orElseThrow(() -> new ToolException("No such error group"));
                    var frames = errors.appFrames(g.frames, 2);
                    if (frames.isEmpty()) throw new ToolException("No application frame in this stack trace");
                    ArrayNode locations = JsonUtil.arr();
                    int i = 0;
                    for (var frame : frames) {
                        var cc = code.context(frame, g.commitSha);
                        if (cc.isEmpty()) continue;
                        ctx.attachCode(id + "#" + i++, cc.get());
                        ObjectNode o = JsonUtil.M.valueToTree(cc.get());
                        o.put("frame", frame.className() + "." + frame.methodName());
                        o.put("failingLineText", cc.get().lines().get(cc.get().line() - cc.get().startLine()));
                        locations.add(o);
                    }
                    if (locations.isEmpty()) throw new ToolException("Source not available (code provider: " + code.providerName() + ")");
                    return JsonUtil.obj().put("commitSha", g.commitSha).set("locations", locations);
                });

        add("get_recent_commits", "List recent commits in the application repository, to correlate an incident with a deploy or code change.",
                params(p("hours", "INTEGER", "How far back to look, 1 to 720. Default 24", false)),
                (a, ctx) -> {
                    int hours = intArg(a, "hours", 24, 1, 720);
                    return JsonUtil.obj().put("codeProvider", code.providerName())
                            .set("commits", JsonUtil.M.valueToTree(code.recentCommits(hours)));
                });

        add("search_knowledge_base", "Semantic search over runbooks, past incident postmortems and team documents. Use it to find known causes and proven fixes.",
                params(p("query", "STRING", "What to look for, e.g. the exception message or symptom", true),
                        p("type", "STRING", "Optional filter: runbook, incident or doc", false)),
                (a, ctx) -> {
                    String q = str(a, "query", 1000, true);
                    String type = str(a, "type", 20, false);
                    var hits = kb.search(q, 4, type);
                    ctx.attachKnowledge(hits);
                    return JsonUtil.obj().set("results", JsonUtil.M.valueToTree(hits));
                });
    }

    private JsonNode queryPrometheus(JsonNode a, ToolContext ctx) {
        String query = str(a, "query", 2000, true);
        if (query.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) throw new IllegalArgumentException("Query contains control characters");
        int lookback = intArg(a, "lookback_minutes", 60, 5, Math.min(1440, limits.maxRangeMinutes()));
        int autoStep = Math.max(limits.minStepSeconds(), (lookback * 60) / 240);
        int step = intArg(a, "step_seconds", autoStep, limits.minStepSeconds(), 3600);
        if ((lookback * 60L) / step > 1000) step = (int) Math.ceil(lookback * 60.0 / 1000);
        String title = str(a, "title", 80, false);

        long end = Instant.now().getEpochSecond();
        long start = end - lookback * 60L;
        JsonNode data = prom.queryRange(query, start, end, step);

        ArrayNode series = JsonUtil.arr();
        int total = 0;
        JsonNode firstPoints = null;
        ObjectNode firstSummary = null;
        for (JsonNode s : data.path("result")) {
            total++;
            if (series.size() >= limits.maxSeries()) continue;
            List<double[]> pts = new ArrayList<>();
            for (JsonNode v : s.path("values")) {
                double val = parse(v.path(1).asText());
                if (!Double.isNaN(val) && !Double.isInfinite(val)) pts.add(new double[]{v.path(0).asDouble(), val});
            }
            ObjectNode sum = summarize(pts);
            ObjectNode labels = (ObjectNode) s.path("metric").deepCopy();
            labels.remove("__name__");
            ObjectNode entry = series.addObject();
            entry.set("labels", labels);
            entry.setAll(sum);
            if (firstPoints == null && !pts.isEmpty()) {
                firstPoints = downsample(pts, 60);
                firstSummary = sum;
            }
        }
        if (firstPoints != null) ctx.attachChart(title == null ? JsonUtil.truncate(query, 60) : title, query, firstPoints, firstSummary);
        ObjectNode out = JsonUtil.obj();
        out.put("query", query).put("windowMinutes", lookback).put("stepSeconds", step)
                .put("from", Instant.ofEpochSecond(start).toString()).put("to", Instant.ofEpochSecond(end).toString())
                .put("seriesCount", total);
        if (total == 0) out.put("note", "No data. Check the metric name and labels with list_metrics; do not treat this as zero.");
        if (total > limits.maxSeries()) out.put("note", "Only the first " + limits.maxSeries() + " series are shown; aggregate with sum by(...) to reduce.");
        out.set("series", series);
        return out;
    }

    /** Deterministic statistics so the model never has to do arithmetic on raw samples. */
    static ObjectNode summarize(List<double[]> pts) {
        ObjectNode o = JsonUtil.obj();
        o.put("points", pts.size());
        if (pts.isEmpty()) return o;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (double[] p : pts) {
            min = Math.min(min, p[1]);
            max = Math.max(max, p[1]);
            sum += p[1];
        }
        int n = pts.size();
        int baseEnd = Math.max(1, (int) (n * 0.6));
        int recentStart = Math.min(n - 1, (int) (n * 0.75));
        double base = avg(pts, 0, baseEnd);
        double recent = avg(pts, recentStart, n);
        o.put("min", round(min)).put("max", round(max)).put("mean", round(sum / n)).put("last", round(pts.get(n - 1)[1]))
                .put("baselineMean", round(base)).put("recentMean", round(recent));
        if (Math.abs(base) > 1e-9) o.put("changePercent", round((recent - base) / Math.abs(base) * 100));
        return o;
    }

    private static double avg(List<double[]> pts, int from, int to) {
        double s = 0;
        int c = 0;
        for (int i = from; i < to; i++) {
            s += pts.get(i)[1];
            c++;
        }
        return c == 0 ? 0 : s / c;
    }

    private static ArrayNode downsample(List<double[]> pts, int max) {
        ArrayNode a = JsonUtil.arr();
        double stride = Math.max(1, pts.size() / (double) max);
        for (double i = 0; i < pts.size(); i += stride) {
            double[] p = pts.get((int) i);
            a.addArray().add((long) p[0]).add(round(p[1]));
        }
        return a;
    }

    private static double round(double v) {
        if (Math.abs(v) >= 100) return Math.round(v * 10) / 10.0;
        return Math.round(v * 10000) / 10000.0;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---- argument helpers -------------------------------------------------

    private static String str(JsonNode a, String field, int maxLen, boolean required) {
        JsonNode v = a.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            if (required) throw new IllegalArgumentException("Missing argument: " + field);
            return null;
        }
        if (!v.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        String s = v.asText().trim();
        if (s.length() > maxLen) throw new IllegalArgumentException(field + " is too long");
        return s;
    }

    private static int intArg(JsonNode a, String field, int def, int min, int max) {
        JsonNode v = a.get(field);
        if (v == null || v.isNull()) return def;
        if (!v.isNumber() && !(v.isTextual() && v.asText().matches("\\d{1,7}"))) throw new IllegalArgumentException(field + " must be a number");
        return Math.max(min, Math.min(max, v.asInt(def)));
    }

    private record Param(String name, String type, String description, boolean required) {}

    private static Param p(String name, String type, String description, boolean required) {
        return new Param(name, type, description, required);
    }

    private static List<Param> params(Param... ps) {
        return List.of(ps);
    }

    private void add(String name, String description, List<Param> ps, java.util.function.BiFunction<JsonNode, ToolContext, JsonNode> fn) {
        ObjectNode d = JsonUtil.obj();
        d.put("name", name).put("description", description);
        if (!ps.isEmpty()) {
            ObjectNode schema = d.putObject("parameters");
            schema.put("type", "OBJECT");
            ObjectNode props = schema.putObject("properties");
            ArrayNode req = JsonUtil.arr();
            for (Param p : ps) {
                props.putObject(p.name()).put("type", p.type()).put("description", p.description());
                if (p.required()) req.add(p.name());
            }
            if (!req.isEmpty()) schema.set("required", req);
        }
        tools.put(name, new AgentTool(name, d, fn));
    }
}
