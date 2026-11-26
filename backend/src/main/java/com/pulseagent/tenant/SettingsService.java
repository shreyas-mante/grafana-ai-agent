package com.pulseagent.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.net.OutboundGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-workspace integration settings. Secrets are encrypted at rest and never returned to
 * clients. Every value is validated, and URLs pass the SSRF guard. The bootstrap workspace
 * falls back to environment variables for anything it hasn't set.
 */
@Service
public class SettingsService {

    public static final String DEFAULT_WS = "ws_default";

    public record Def(String name, String group, String label, boolean secret, Consumer<String> validator) {}

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final JdbcTemplate db;
    private final Crypto crypto;
    private final AppProperties props;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public SettingsService(JdbcTemplate db, Crypto crypto, AppProperties props) {
        this.db = db;
        this.crypto = crypto;
        this.props = props;
        Consumer<String> url = v -> OutboundGuard.check(v, props.platform().allowPrivateTargets());
        Consumer<String> text = v -> require(v.length() <= 200 && !v.matches(".*[\\p{Cntrl}].*"), "Invalid value");
        Consumer<String> secret = v -> require(v.length() <= 2000 && !v.matches(".*[\\p{Cntrl}].*"), "Invalid secret");
        def("llm.provider", "ai", "AI provider", false, v -> require(Set.of("none", "gemini", "anthropic", "openai").contains(v), "Choose none, gemini, anthropic or openai"));
        def("llm.model", "ai", "Model", false, v -> require(v.matches("[A-Za-z0-9._:/-]{1,100}"), "Invalid model name"));
        def("llm.apiKey", "ai", "API key", true, secret);
        def("llm.baseUrl", "ai", "Base URL (OpenAI-compatible only)", false, url);
        def("prometheus.url", "prometheus", "Prometheus URL", false, url);
        def("prometheus.username", "prometheus", "Username (Grafana Cloud instance ID)", false, text);
        def("prometheus.token", "prometheus", "Token", true, secret);
        def("loki.url", "loki", "Loki URL", false, url);
        def("loki.username", "loki", "Username", false, text);
        def("loki.token", "loki", "Token", true, secret);
        def("tempo.url", "tempo", "Tempo URL", false, url);
        def("tempo.username", "tempo", "Username", false, text);
        def("tempo.token", "tempo", "Token", true, secret);
        def("grafana.url", "grafana", "Grafana URL", false, url);
        def("grafana.token", "grafana", "Service account token", true, secret);
        def("github.repo", "code", "GitHub repository (owner/name)", false, v -> require(v.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"), "Use owner/name"));
        def("github.token", "code", "Read-only token", true, secret);
        def("github.writeToken", "code", "Issues write token (for approved actions)", true, secret);
        def("code.packages", "code", "Your code's package prefixes (comma-separated)", false, v -> require(v.matches("[A-Za-z0-9_.,\\s]{0,300}"), "Use package names separated by commas"));
        def("slack.webhookUrl", "notify", "Slack incoming webhook URL", true, v -> require(v.startsWith("https://hooks.slack.com/services/") && v.length() < 300 && !v.contains(" "), "Must be a https://hooks.slack.com/services/ URL"));
        def("notify.on", "notify", "Notify Slack for", false, v -> require(Set.of("alerts", "all", "none").contains(v), "Choose alerts, all or none"));
        def("actions.webhookUrl", "actions", "Remediation webhook URL", false, url);
        def("actions.webhookSecret", "actions", "Webhook signing secret", true, v -> require(v.length() >= 16 && v.length() <= 200, "Use at least 16 characters"));
    }

    private void def(String name, String group, String label, boolean secret, Consumer<String> validator) {
        defs.put(name, new Def(name, group, label, secret, validator));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    public String get(String ws, String name) {
        String v = load(ws).get(name);
        if (v != null) return v;
        return DEFAULT_WS.equals(ws) ? envDefault(name) : null;
    }

    public String getOr(String ws, String name, String fallback) {
        String v = get(ws, name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * Whether a URL setting may point at private networks: only when the operator enabled it,
     * or when the value is operator-provided through environment variables (bootstrap workspace).
     */
    public boolean allowPrivate(String ws, String name) {
        return props.platform().allowPrivateTargets() || (DEFAULT_WS.equals(ws) && load(ws).get(name) == null);
    }

    public boolean has(String ws, String name) {
        String v = get(ws, name);
        return v != null && !v.isBlank();
    }

    /** Throws IllegalArgumentException with a user-facing message if the value is invalid. */
    public void validate(String name, String value) {
        Def d = defs.get(name);
        if (d == null) throw new IllegalArgumentException("Unknown setting: " + JsonUtil.truncate(name, 40));
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) d.validator().accept(v);
    }

    /** Sets or clears (empty value) a setting after validation. */
    public void set(String ws, String name, String value, Actor actor, AuditService audit) {
        Def d = defs.get(name);
        if (d == null) throw new IllegalArgumentException("Unknown setting: " + JsonUtil.truncate(name, 40));
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) d.validator().accept(v);
        db.update("delete from workspace_settings where workspace_id = ? and name = ?", ws, name);
        if (!v.isEmpty()) {
            db.update("insert into workspace_settings(workspace_id, name, setting_value, secret, updated_at) values (?,?,?,?,?)",
                    ws, name, d.secret() ? crypto.encrypt(v) : v, d.secret(), Timestamp.from(Instant.now()));
        }
        cache.remove(ws);
        audit.log(actor, v.isEmpty() ? "setting.cleared" : "setting.updated", name);
    }

    public void setRaw(String ws, String name, String value) {
        db.update("delete from workspace_settings where workspace_id = ? and name = ?", ws, name);
        db.update("insert into workspace_settings(workspace_id, name, setting_value, secret, updated_at) values (?,?,?,?,?)",
                ws, name, value, false, Timestamp.from(Instant.now()));
        cache.remove(ws);
    }

    /** View for the UI: plain values for non-secrets, only "set" for secrets. */
    public List<Map<String, Object>> describe(String ws) {
        return defs.values().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name());
            m.put("group", d.group());
            m.put("label", d.label());
            m.put("secret", d.secret());
            String v = get(ws, d.name());
            if (d.secret()) m.put("set", v != null && !v.isBlank());
            else m.put("value", v == null ? "" : v);
            return m;
        }).toList();
    }

    /** Hash of the settings in a group, used to cache per-workspace clients safely. */
    public String fingerprint(String ws, String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(n).append('=').append(get(ws, n)).append('\n');
        return com.pulseagent.security.Hashing.sha256Hex(sb.toString());
    }

    public JsonNode topology(String ws) {
        String t = load(ws).get("topology");
        if (t == null) return JsonUtil.obj();
        try {
            return JsonUtil.M.readTree(t);
        } catch (Exception e) {
            return JsonUtil.obj();
        }
    }

    private Map<String, String> load(String ws) {
        return cache.computeIfAbsent(ws, k -> {
            Map<String, String> m = new HashMap<>();
            db.query("select name, setting_value, secret from workspace_settings where workspace_id = ?", rs -> {
                String v = rs.getString(2);
                m.put(rs.getString(1), rs.getBoolean(3) ? crypto.decrypt(v) : v);
            }, k);
            return m;
        });
    }

    private String envDefault(String name) {
        return switch (name) {
            case "llm.provider" -> props.llm().configured() ? "gemini" : null;
            case "llm.apiKey" -> props.llm().apiKey();
            case "llm.model" -> props.llm().configured() ? props.llm().model() : null;
            case "prometheus.url" -> props.prometheus().url();
            case "prometheus.username" -> props.prometheus().username();
            case "prometheus.token" -> props.prometheus().bearerToken();
            case "github.repo" -> props.code().githubRepo();
            case "github.token" -> props.code().githubToken();
            case "code.packages" -> props.code().appPackages() == null ? null : String.join(",", props.code().appPackages());
            default -> null;
        };
    }
}
