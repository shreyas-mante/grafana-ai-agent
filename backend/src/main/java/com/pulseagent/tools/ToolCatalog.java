package com.pulseagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.actions.ActionService;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.errors.ErrorEventRequest;
import com.pulseagent.errors.ErrorGroup;
import com.pulseagent.errors.ErrorStore;
import com.pulseagent.llm.Llm;
import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.tenant.WorkspaceEnv;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Allowlist of read-only tools. A workspace only sees tools for integrations it configured.
 * Arguments from the model are untrusted: every value is type-checked, bounded and validated.
 */
@Component
public class ToolCatalog {

    @FunctionalInterface
    interface Exec {
        JsonNode run(JsonNode args, ToolContext ctx, WorkspaceEnv env);
    }

    private record Tool(Llm.ToolSpec spec, Predicate<WorkspaceEnv> available, Exec exec) {}

    private static final int MAX_OUTPUT_CHARS = 12_000;
    private static final String SERVICE = "[A-Za-z0-9._-]{1,100}";

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ErrorStore errors;
    private final KnowledgeBase kb;
    private final ActionService actions;
    private final AppProperties.Limits limits;

    public ToolCatalog(ErrorStore errors, KnowledgeBase kb, ActionService actions, AppProperties props) {
        this.errors = errors;
        this.kb = kb;
        this.actions = actions;
        this.limits = props.limits();
        register();
    }

    public List<Llm.ToolSpec> specs(WorkspaceEnv env) {
        return tools.values().stream().filter(t -> t.available().test(env)).map(Tool::spec).toList();
    }

    /** Executes an allowlisted tool. Never throws: failures become an {error} result the model can read. */
    public JsonNode execute(String name, JsonNode args, ToolContext ctx, WorkspaceEnv env) {
        Tool tool = tools.get(name);
        if (tool == null || !tool.available().test(env)) return JsonUtil.obj().put("error", "Unknown tool: " + JsonUtil.truncate(name, 60));
        try {
            JsonNode out = tool.exec().run(args == null || !args.isObject() ? JsonUtil.obj() : args, ctx, env);
            String s = JsonUtil.write(out);
            if (s.length() > MAX_OUTPUT_CHARS) return JsonUtil.obj().put("truncated", true).put("partial", s.substring(0, MAX_OUTPUT_CHARS));
            return out;
        } catch (ToolException | IllegalArgumentException e) {
            return JsonUtil.obj().put("error", e.getMessage());
        } catch (Exception e) {
            return JsonUtil.obj().put("error", "Tool failed: " + e.getClass().getSimpleName());
        }
    }

    private void register() {
        Predicate<WorkspaceEnv> prom = e -> e.prometheus().isPresent();
        Predicate<WorkspaceEnv> code = e -> e.code().isPresent();
        Predicate<WorkspaceEnv> loki = e -> e.loki().isPresent();
        Predicate<WorkspaceEnv> tempo = e -> e.tempo().isPresent();
        Predicate<WorkspaceEnv> grafana = e -> e.grafana().isPresent();
        Predicate<WorkspaceEnv> always = e -> true;

        add("list_metrics", "List Prometheus metric names, optionally filtered by a case-insensitive substring. Use this first to discover real metric names instead of guessing.",
                List.of(p("filter", "string", "Substring to filter metric names, e.g. 'http' or 'pool'", false)), prom,
                (a, ctx, env) -> {
                    String filter = str(a, "filter", 100, false);
                    ArrayNode out = JsonUtil.arr();
                    int total = 0;
                    for (JsonNode n : env.prometheus().get().metricNames()) {
                        String s = n.asText();
                        if (filter == null || s.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                            total++;
                            if (out.size() < 150) out.add(s);
                        }
                    }
                    return JsonUtil.obj().put("matching", total).set("metrics", out);
                });

        add("query_prometheus", "Run a read-only PromQL range query over a recent window and get per-series statistics, including a baseline (earlier part of the window) versus recent comparison.",
                List.of(p("query", "string", "PromQL expression", true),
                        p("lookback_minutes", "integer", "Window size ending now, 5 to 1440. Default 60", false),
                        p("step_seconds", "integer", "Resolution in seconds. Default chosen automatically", false),
                        p("title", "string", "Short human-readable label for this query, e.g. 'p95 latency'", false)),
                prom, this::queryPrometheus);

        add("get_firing_alerts", "List currently firing and pending Prometheus alerts with their labels and annotations.", List.of(), prom,
                (a, ctx, env) -> {
                    ArrayNode out = JsonUtil.arr();
                    for (JsonNode al : env.prometheus().get().alerts().path("alerts")) {
                        if (out.size() >= 30) break;
                        ObjectNode o = out.addObject();
                        o.put("state", al.path("state").asText()).put("activeAt", al.path("activeAt").asText());
                        o.set("labels", al.path("labels"));
                        o.set("annotations", al.path("annotations"));
                    }
                    return JsonUtil.obj().set("alerts", out);
                });

        add("get_recent_errors", "List recent application exceptions reported by the PulseAgent SDK, grouped and counted, with the top application stack frame.",
                List.of(p("service", "string", "Service name to filter by, optional", false)), always,
                (a, ctx, env) -> {
                    String service = service(a, false);
                    ArrayNode out = JsonUtil.arr();
                    for (ErrorGroup g : errors.recent(env.workspaceId(), service, 10)) {
                        ObjectNode o = out.addObject();
                        o.put("errorId", g.id()).put("service", g.service()).put("exception", g.exceptionClass())
                                .put("message", JsonUtil.truncate(g.message(), 500)).put("count", g.count())
                                .put("firstSeen", g.firstSeen().toString()).put("lastSeen", g.lastSeen().toString())
                                .put("endpoint", (g.httpMethod() == null ? "" : g.httpMethod() + " ") + (g.path() == null ? "" : g.path()))
                                .put("commitSha", g.commitSha());
                        ErrorStore.appFrames(g.frames(), env.appPackages(), 1).forEach(f ->
                                o.put("topAppFrame", f.className() + "." + f.methodName() + "(" + f.fileName() + ":" + f.lineNumber() + ")"));
                        if (!g.causes().isEmpty()) {
                            ArrayNode causes = o.putArray("causes");
                            g.causes().forEach(c -> causes.add(c.exceptionClass() + ": " + JsonUtil.truncate(c.message(), 200)));
                        }
                    }
                    return JsonUtil.obj().put("codeProvider", env.codeProviderName()).set("errors", out);
                });

        add("get_code_context", "For an error group, resolve the top application stack frames (where it was thrown, then the caller) in the source at the deployed commit: file path, exact line, surrounding code, and git blame (author, commit, date, pull request). The root cause is often in the caller, not where the exception was thrown.",
                List.of(p("error_id", "string", "errorId from get_recent_errors", true)), code, this::codeContext);

        add("get_recent_commits", "List recent commits in the application repository, to correlate an incident with a deploy or code change.",
                List.of(p("hours", "integer", "How far back to look, 1 to 720. Default 24", false)), code,
                (a, ctx, env) -> JsonUtil.obj().put("codeProvider", env.codeProviderName())
                        .set("commits", JsonUtil.M.valueToTree(env.code().get().recentCommits(intArg(a, "hours", 24, 1, 720)))));

        add("search_knowledge_base", "Semantic search over runbooks, past incident postmortems and team documents. Use it to find known causes and proven fixes.",
                List.of(p("query", "string", "What to look for, e.g. the exception message or symptom", true),
                        p("type", "string", "Optional filter: runbook, incident or doc", false)), always,
                (a, ctx, env) -> {
                    String type = str(a, "type", 20, false);
                    if (type != null && !type.matches("runbook|incident|doc")) type = null;
                    var hits = kb.search(env.workspaceId(), str(a, "query", 1000, true), 4, type);
                    ctx.attachKnowledge(hits);
                    return JsonUtil.obj().set("results", JsonUtil.M.valueToTree(hits));
                });

        add("search_logs", "Search application logs in Loki with LogQL, newest first. Start with a stream selector, e.g. {service=\"payment-service\"} |= \"error\".",
                List.of(p("logql", "string", "LogQL query beginning with a {label=\"value\"} stream selector", true),
                        p("lookback_minutes", "integer", "Window ending now, 5 to 1440. Default 30", false),
                        p("limit", "integer", "Max lines, 1 to 100. Default 50", false)), loki,
                (a, ctx, env) -> {
                    String q = str(a, "logql", 1000, true);
                    if (!q.startsWith("{")) throw new IllegalArgumentException("LogQL must start with a stream selector such as {service=\"x\"}");
                    return env.loki().get().search(q, intArg(a, "lookback_minutes", 30, 5, 1440), intArg(a, "limit", 50, 1, 100));
                });

        add("search_traces", "Find recent distributed traces for a service in Tempo, optionally only slow ones.",
                List.of(p("service", "string", "service.name of the traces", true),
                        p("lookback_minutes", "integer", "Window ending now, 5 to 1440. Default 30", false),
                        p("min_duration_ms", "integer", "Only traces slower than this. Default 0", false),
                        p("limit", "integer", "Max traces, 1 to 20. Default 10", false)), tempo,
                (a, ctx, env) -> env.tempo().get().search(service(a, true), intArg(a, "lookback_minutes", 30, 5, 1440),
                        intArg(a, "min_duration_ms", 0, 0, 600_000), intArg(a, "limit", 10, 1, 20)));

        add("get_trace", "Summarise one trace from Tempo: slowest spans and error spans, by service.",
                List.of(p("trace_id", "string", "Trace ID (hex)", true)), tempo,
                (a, ctx, env) -> {
                    String id = str(a, "trace_id", 64, true);
                    if (!id.matches("[0-9a-fA-F]{16,32}")) throw new IllegalArgumentException("Invalid trace_id");
                    return env.tempo().get().trace(id);
                });

        add("search_dashboards", "Search Grafana dashboards by title.",
                List.of(p("query", "string", "Words in the dashboard title, e.g. 'payment'", false)), grafana,
                (a, ctx, env) -> env.grafana().get().search(str(a, "query", 100, false)));

        add("get_dashboard", "Get a Grafana dashboard's panels and their queries, to reuse the team's proven PromQL/LogQL.",
                List.of(p("uid", "string", "Dashboard uid from search_dashboards", true)), grafana,
                (a, ctx, env) -> {
                    String uid = str(a, "uid", 60, true);
                    if (!uid.matches("[A-Za-z0-9_-]{1,60}")) throw new IllegalArgumentException("Invalid uid");
                    return env.grafana().get().dashboard(uid);
                });

        add("get_service_dependencies", "Look up a service in the workspace's service map: what it depends on (downstream), what depends on it (upstream), its owner and runbook. Use it to find blast radius and suspects.",
                List.of(p("service", "string", "Service name", true)), WorkspaceEnv::hasTopology,
                (a, ctx, env) -> dependencies(env.topology(), service(a, true)));

        add("propose_remediation", "Propose an action for a human to approve, such as rolling back a commit, restarting or scaling a service, or opening a GitHub issue. Nothing runs until a workspace owner approves it. Only propose when the evidence supports it.",
                List.of(p("kind", "string", "github_issue, or for the team's automation webhook one of: rollback, restart, scale, disable_feature, other", true),
                        p("title", "string", "Short title, e.g. 'Roll back commit 5aef153 in payment-service'", true),
                        p("target", "string", "Service, commit or resource the action applies to", true),
                        p("reason", "string", "Why, citing the evidence", true),
                        p("details", "string", "Specifics: parameters, the commit to revert, issue body notes", false)),
                WorkspaceEnv::actionsEnabled,
                (a, ctx, env) -> {
                    String kind = str(a, "kind", 40, true);
                    if (!kind.matches("github_issue|rollback|restart|scale|disable_feature|other")) throw new IllegalArgumentException("Unsupported kind");
                    ObjectNode payload = JsonUtil.obj();
                    payload.put("kind", kind).put("target", str(a, "target", 200, true)).put("details", str(a, "details", 4000, false));
                    String type = kind.equals("github_issue") ? "github_issue" : "webhook";
                    String id = actions.propose(env.workspaceId(), ctx.investigationId(), type, str(a, "title", 200, true), payload, str(a, "reason", 2000, true));
                    ctx.attachAction(id);
                    return JsonUtil.obj().put("actionId", id).put("status", "PENDING_APPROVAL")
                            .put("note", "A workspace owner must approve this before anything happens.");
                });
    }

    private JsonNode codeContext(JsonNode a, ToolContext ctx, WorkspaceEnv env) {
        String id = str(a, "error_id", 40, true);
        if (!id.matches("err_[0-9a-f]{16}")) throw new IllegalArgumentException("Invalid error_id");
        ErrorGroup g = errors.get(env.workspaceId(), id).orElseThrow(() -> new ToolException("No such error group"));
        List<ErrorEventRequest.Frame> frames = ErrorStore.appFrames(g.frames(), env.appPackages(), 2);
        if (frames.isEmpty()) throw new ToolException("No application frame in this stack trace (set your code package prefixes in Settings)");
        ArrayNode locations = JsonUtil.arr();
        int i = 0;
        for (var frame : frames) {
            var cc = env.code().get().context(frame, g.commitSha());
            if (cc.isEmpty()) continue;
            var c = cc.get();
            ctx.attachCode(id + "#" + i++, c);
            ObjectNode o = JsonUtil.M.valueToTree(c);
            o.put("frame", frame.className() + "." + frame.methodName());
            o.put("failingLineText", c.lines().get(c.line() - c.startLine()));
            o.remove("lineCommits");
            if (!c.recentChanges().isEmpty()) {
                var newest = c.recentChanges().get(0);
                ArrayNode changed = o.putArray("linesChangedByNewestCommit");
                for (int k = 0; k < c.lines().size(); k++) {
                    if (newest.shortCommit().equals(c.lineCommits().get(k))) {
                        changed.addObject().put("line", c.startLine() + k).put("text", c.lines().get(k).trim());
                    }
                }
                o.put("newestCommitNearFailure", newest.shortCommit() + " by " + newest.author() + " at " + newest.date() + ": " + newest.summary());
            }
            locations.add(o);
        }
        if (locations.isEmpty()) throw new ToolException("Source not available (code provider: " + env.codeProviderName() + ")");
        return JsonUtil.obj().put("commitSha", g.commitSha()).set("locations", locations);
    }

    static ObjectNode dependencies(JsonNode topology, String service) {
        ObjectNode out = JsonUtil.obj();
        out.put("service", service);
        ArrayNode downstream = out.putArray("dependsOn");
        ArrayNode upstream = out.putArray("dependedOnBy");
        boolean found = false;
        for (JsonNode s : topology.path("services")) {
            String name = s.path("name").asText();
            if (name.equalsIgnoreCase(service)) {
                found = true;
                s.path("dependsOn").forEach(downstream::add);
                out.put("owner", s.path("owner").asText(null));
                out.put("runbook", s.path("runbook").asText(null));
                out.put("tier", s.path("tier").asText(null));
            }
            for (JsonNode d : s.path("dependsOn")) if (d.asText().equalsIgnoreCase(service)) upstream.add(name);
        }
        if (!found) out.put("note", "This service isn't in the service map.");
        return out;
    }

    private JsonNode queryPrometheus(JsonNode a, ToolContext ctx, WorkspaceEnv env) {
        String query = str(a, "query", 2000, true);
        if (query.chars().anyMatch(c -> c < 0x20 && c != '\n' && c != '\t')) throw new IllegalArgumentException("Query contains control characters");
        int lookback = intArg(a, "lookback_minutes", 60, 5, Math.min(1440, limits.maxRangeMinutes()));
        int autoStep = Math.max(limits.minStepSeconds(), (lookback * 60) / 240);
        int step = intArg(a, "step_seconds", autoStep, limits.minStepSeconds(), 3600);
        if ((lookback * 60L) / step > 1000) step = (int) Math.ceil(lookback * 60.0 / 1000);
        String title = str(a, "title", 80, false);

        long end = Instant.now().getEpochSecond();
        long start = end - lookback * 60L;
        JsonNode data = env.prometheus().get().queryRange(query, start, end, step);

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
        double base = avg(pts, 0, Math.max(1, (int) (n * 0.6)));
        double recent = avg(pts, Math.min(n - 1, (int) (n * 0.75)), n);
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

    private static String service(JsonNode a, boolean required) {
        String s = str(a, "service", 100, required);
        if (s != null && !s.matches(SERVICE)) throw new IllegalArgumentException("Invalid service name");
        return s;
    }

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

    private static Llm.Param p(String name, String type, String description, boolean required) {
        return new Llm.Param(name, type, description, required);
    }

    private void add(String name, String description, List<Llm.Param> ps, Predicate<WorkspaceEnv> available, Exec fn) {
        tools.put(name, new Tool(new Llm.ToolSpec(name, description, ps), available, fn));
    }
}
