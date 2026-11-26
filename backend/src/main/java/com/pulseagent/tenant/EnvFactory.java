package com.pulseagent.tenant;

import com.pulseagent.code.CodeIntel;
import com.pulseagent.config.AppProperties;
import com.pulseagent.integrations.GrafanaClient;
import com.pulseagent.integrations.LokiClient;
import com.pulseagent.integrations.PrometheusClient;
import com.pulseagent.integrations.TempoClient;
import com.pulseagent.llm.Llm;
import com.pulseagent.llm.LlmFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Builds and caches each workspace's integration clients; rebuilt when its settings change. */
@Component
public class EnvFactory {

    private record Cached(String fingerprint, WorkspaceEnv env) {}

    private static final String[] KEYS = {"prometheus.url", "prometheus.username", "prometheus.token", "loki.url", "loki.username",
            "loki.token", "tempo.url", "tempo.username", "tempo.token", "grafana.url", "grafana.token", "github.repo", "github.token",
            "code.packages", "actions.webhookUrl", "github.writeToken", "topology"};

    private final SettingsService settings;
    private final LlmFactory llm;
    private final AppProperties props;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public EnvFactory(SettingsService settings, LlmFactory llm, AppProperties props) {
        this.settings = settings;
        this.llm = llm;
        this.props = props;
    }

    public WorkspaceEnv forWorkspace(String ws) {
        String fp = settings.fingerprint(ws, KEYS) + settings.topology(ws).toString().hashCode();
        Cached c = cache.get(ws);
        Optional<Llm.Provider> provider = safe(() -> llm.forWorkspace(ws), new ArrayList<>()).flatMap(x -> x);
        if (c != null && c.fingerprint().equals(fp)) {
            WorkspaceEnv e = c.env();
            return new WorkspaceEnv(ws, provider, e.prometheus(), e.loki(), e.tempo(), e.grafana(), e.code(), e.appPackages(),
                    e.topology(), e.actionsEnabled(), e.problems());
        }
        List<String> problems = new ArrayList<>();
        Optional<PrometheusClient> prom = settings.has(ws, "prometheus.url")
                ? safe(() -> new PrometheusClient(settings.get(ws, "prometheus.url"), settings.get(ws, "prometheus.username"),
                settings.get(ws, "prometheus.token"), settings.allowPrivate(ws, "prometheus.url")), problems) : Optional.empty();
        Optional<LokiClient> loki = settings.has(ws, "loki.url")
                ? safe(() -> new LokiClient(settings.get(ws, "loki.url"), settings.get(ws, "loki.username"),
                settings.get(ws, "loki.token"), settings.allowPrivate(ws, "loki.url")), problems) : Optional.empty();
        Optional<TempoClient> tempo = settings.has(ws, "tempo.url")
                ? safe(() -> new TempoClient(settings.get(ws, "tempo.url"), settings.get(ws, "tempo.username"),
                settings.get(ws, "tempo.token"), settings.allowPrivate(ws, "tempo.url")), problems) : Optional.empty();
        Optional<GrafanaClient> grafana = settings.has(ws, "grafana.url")
                ? safe(() -> new GrafanaClient(settings.get(ws, "grafana.url"), settings.get(ws, "grafana.token"),
                settings.allowPrivate(ws, "grafana.url")), problems) : Optional.empty();
        Optional<CodeIntel> code = Optional.empty();
        if (settings.has(ws, "github.repo") && settings.has(ws, "github.token")) {
            code = safe(() -> CodeIntel.github(settings.get(ws, "github.repo"), settings.get(ws, "github.token")), problems);
        } else if (SettingsService.DEFAULT_WS.equals(ws) && props.code().localConfigured()) {
            code = safe(() -> CodeIntel.local(props.code().localRepoPath()), problems);
        }
        List<String> packages = Arrays.stream(settings.getOr(ws, "code.packages", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean actions = settings.has(ws, "actions.webhookUrl") || (settings.has(ws, "github.repo") && settings.has(ws, "github.writeToken"));
        WorkspaceEnv env = new WorkspaceEnv(ws, provider, prom, loki, tempo, grafana, code, packages, settings.topology(ws), actions, List.copyOf(problems));
        cache.put(ws, new Cached(fp, env));
        return env;
    }

    private static <T> Optional<T> safe(Supplier<T> s, List<String> problems) {
        try {
            return Optional.ofNullable(s.get());
        } catch (Exception e) {
            problems.add(e.getMessage());
            return Optional.empty();
        }
    }
}
