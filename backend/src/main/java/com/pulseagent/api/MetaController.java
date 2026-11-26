package com.pulseagent.api;

import com.pulseagent.code.CodeIntelService;
import com.pulseagent.llm.EmbeddingService;
import com.pulseagent.llm.GeminiClient;
import com.pulseagent.rag.KnowledgeBase;
import com.pulseagent.tools.PrometheusClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class MetaController {

    private final GeminiClient llm;
    private final EmbeddingService embeddings;
    private final CodeIntelService code;
    private final KnowledgeBase kb;
    private final PrometheusClient prom;

    public MetaController(GeminiClient llm, EmbeddingService embeddings, CodeIntelService code, KnowledgeBase kb, PrometheusClient prom) {
        this.llm = llm;
        this.embeddings = embeddings;
        this.code = code;
        this.kb = kb;
        this.prom = prom;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Integration status for the admin UI. Reports whether secrets are set, never their values. */
    @GetMapping("/api/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aiMode", llm.available() ? "ai" : "offline");
        m.put("model", llm.available() ? llm.model() : null);
        m.put("embeddings", embeddings.mode());
        m.put("codeProvider", code.providerName());
        m.put("prometheusHost", prom.host());
        m.put("knowledgeDocs", kb.size());
        return m;
    }
}
