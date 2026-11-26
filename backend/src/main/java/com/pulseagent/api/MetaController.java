package com.pulseagent.api;

import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.tenant.Actor;
import com.pulseagent.tenant.EnvFactory;
import com.pulseagent.tenant.WorkspaceEnv;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class MetaController {

    private final EnvFactory envs;
    private final KnowledgeBase kb;

    public MetaController(EnvFactory envs, KnowledgeBase kb) {
        this.envs = envs;
        this.kb = kb;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Integration status for the UI. Reports what is connected, never secret values. */
    @GetMapping("/api/meta")
    public Map<String, Object> meta() {
        WorkspaceEnv env = envs.forWorkspace(Actor.current().workspaceId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiMode", env.llm().isPresent() ? "ai" : "offline");
        m.put("model", env.llm().map(p -> p.name() + " · " + p.model()).orElse(null));
        m.put("embeddings", kb.mode(env.workspaceId()));
        m.put("codeProvider", env.codeProviderName());
        m.put("prometheusHost", env.prometheus().map(p -> p.host()).orElse(null));
        m.put("loki", env.loki().isPresent());
        m.put("tempo", env.tempo().isPresent());
        m.put("grafana", env.grafana().isPresent());
        m.put("serviceMap", env.hasTopology());
        m.put("actions", env.actionsEnabled());
        m.put("knowledgeDocs", kb.size(env.workspaceId()));
        m.put("problems", env.problems());
        return m;
    }
}
