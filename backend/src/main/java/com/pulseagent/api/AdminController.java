package com.pulseagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.llm.Llm;
import com.pulseagent.notify.NotificationService;
import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.ApiKeyService;
import com.pulseagent.tenant.AuditService;
import com.pulseagent.tenant.EnvFactory;
import com.pulseagent.tenant.SettingsService;
import com.pulseagent.tenant.UserService;
import com.pulseagent.tenant.WorkspaceEnv;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Workspace administration. Owner role only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record SettingsUpdate(@NotNull @Size(max = 40) Map<@Size(max = 60) String, @Size(max = 2000) String> values) {}

    public record KeyRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Pattern(regexp = "admin|ingest|widget") String scope,
                             @Size(max = 10) List<@Pattern(regexp = "https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?") String> allowedOrigins) {}

    public record InviteRequest(@NotBlank @Pattern(regexp = "OWNER|MEMBER") String role) {}

    public record TopologyRequest(@NotNull JsonNode topology) {}

    private final SettingsService settings;
    private final ApiKeyService keys;
    private final UserService users;
    private final AuditService audit;
    private final EnvFactory envs;
    private final NotificationService notify;
    private final AppProperties props;

    public AdminController(SettingsService settings, ApiKeyService keys, UserService users, AuditService audit, EnvFactory envs,
                           NotificationService notify, AppProperties props) {
        this.settings = settings;
        this.keys = keys;
        this.users = users;
        this.audit = audit;
        this.envs = envs;
        this.notify = notify;
        this.props = props;
    }

    @GetMapping("/settings")
    public List<Map<String, Object>> settings() {
        return settings.describe(Actor.current().workspaceId());
    }

    /** Applies all values or none: everything is validated before anything is saved. */
    @PutMapping("/settings")
    public ResponseEntity<?> update(@Valid @RequestBody SettingsUpdate req) {
        Actor a = Actor.current();
        Set<String> known = new HashSet<>();
        settings.describe(a.workspaceId()).forEach(d -> known.add((String) d.get("name")));
        for (String k : req.values().keySet()) if (!known.contains(k)) throw new IllegalArgumentException("Unknown setting: " + JsonUtil.truncate(k, 40));
        req.values().forEach((k, v) -> {
            try {
                settings.validate(k, v);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(k + ": " + e.getMessage());
            }
        });
        req.values().forEach((k, v) -> settings.set(a.workspaceId(), k, v, a, audit));
        return ResponseEntity.ok(settings.describe(a.workspaceId()));
    }

    /** Checks one integration with a harmless read-only call. */
    @PostMapping("/settings/test/{integration}")
    public Map<String, Object> test(@PathVariable String integration) {
        String ws = Actor.current().workspaceId();
        WorkspaceEnv env = envs.forWorkspace(ws);
        try {
            String detail = switch (integration) {
                case "ai" -> {
                    Llm.Provider p = env.llm().orElseThrow(() -> new IllegalArgumentException("Choose a provider and add an API key first"));
                    Llm.Session s = p.session("Reply with the single word OK.", List.of());
                    s.user("ping");
                    String t = s.next(false).text();
                    yield p.name() + " · " + p.model() + " replied: " + JsonUtil.truncate(t.trim(), 40);
                }
                case "prometheus" -> env.prometheus().orElseThrow(() -> new IllegalArgumentException(problem(env, "Set the Prometheus URL first")))
                        .metricNames().size() + " metrics found";
                case "loki" -> env.loki().orElseThrow(() -> new IllegalArgumentException(problem(env, "Set the Loki URL first")))
                        .labels() + " log labels found";
                case "tempo" -> env.tempo().orElseThrow(() -> new IllegalArgumentException(problem(env, "Set the Tempo URL first")))
                        .tags() + " trace tags found";
                case "grafana" -> env.grafana().orElseThrow(() -> new IllegalArgumentException(problem(env, "Set the Grafana URL first")))
                        .search("").path("dashboards").size() + " dashboards visible";
                case "code" -> env.code().orElseThrow(() -> new IllegalArgumentException(problem(env, "Set the GitHub repository and token first")))
                        .recentCommits(24 * 30).size() + " commits in the last 30 days";
                case "slack" -> {
                    notify.test(ws);
                    yield "Test message sent";
                }
                default -> throw new IllegalArgumentException("Unknown integration");
            };
            return Map.of("ok", true, "detail", detail);
        } catch (Exception e) {
            return Map.of("ok", false, "detail", JsonUtil.truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 300));
        }
    }

    private static String problem(WorkspaceEnv env, String fallback) {
        return env.problems().isEmpty() ? fallback : String.join("; ", env.problems());
    }

    @GetMapping("/topology")
    public JsonNode topology() {
        return settings.topology(Actor.current().workspaceId());
    }

    @PutMapping("/topology")
    public ResponseEntity<?> setTopology(@Valid @RequestBody TopologyRequest req) {
        Actor a = Actor.current();
        JsonNode t = req.topology();
        JsonNode services = t.path("services");
        if (!services.isArray() || services.size() > 500) throw new IllegalArgumentException("Use {\"services\": [...]} with at most 500 services");
        for (JsonNode s : services) {
            if (!s.path("name").asText("").matches("[A-Za-z0-9._-]{1,100}")) throw new IllegalArgumentException("Every service needs a valid name");
            for (JsonNode d : s.path("dependsOn")) if (!d.asText("").matches("[A-Za-z0-9._-]{1,100}")) throw new IllegalArgumentException("Invalid dependency name in " + s.path("name").asText());
            if (s.path("owner").asText("").length() > 100 || s.path("runbook").asText("").length() > 300) throw new IllegalArgumentException("Owner or runbook is too long");
        }
        String json = JsonUtil.write(t);
        if (json.length() > 100_000) throw new IllegalArgumentException("Service map is too large");
        settings.setRaw(a.workspaceId(), "topology", json);
        audit.log(a, "topology.updated", services.size() + " services");
        return ResponseEntity.ok(t);
    }

    @GetMapping("/keys")
    public List<Map<String, Object>> keys() {
        return keys.list(Actor.current().workspaceId());
    }

    @PostMapping("/keys")
    public ResponseEntity<?> createKey(@Valid @RequestBody KeyRequest req) {
        Actor a = Actor.current();
        if ("widget".equals(req.scope()) && (req.allowedOrigins() == null || req.allowedOrigins().isEmpty())) {
            throw new IllegalArgumentException("Widget keys need at least one allowed origin, like https://admin.example.com");
        }
        var created = keys.create(a.workspaceId(), req.name().replaceAll("[\\p{Cntrl}]", ""), req.scope(),
                "widget".equals(req.scope()) ? req.allowedOrigins() : List.of(), a.display());
        audit.log(a, "key.created", req.scope() + ": " + req.name());
        return ResponseEntity.status(201).body(Map.of("id", created.id(), "key", created.plainKey(),
                "note", "Copy this key now. It won't be shown again."));
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<?> revoke(@PathVariable String id) {
        Actor a = Actor.current();
        if (!keys.revoke(a.workspaceId(), id)) return ResponseEntity.notFound().build();
        audit.log(a, "key.revoked", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members")
    public List<Map<String, Object>> members() {
        return users.members(Actor.current().workspaceId());
    }

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable String userId) {
        users.removeMember(Actor.current(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invites")
    public Map<String, Object> invite(@Valid @RequestBody InviteRequest req) {
        String token = users.createInvite(Actor.current(), req.role());
        String base = props.platform().publicUrl() == null ? "" : props.platform().publicUrl().replaceAll("/+$", "");
        return Map.of("link", base + "/#/join/" + token, "expiresInDays", 7);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return audit.list(Actor.current().workspaceId(), 200);
    }
}
