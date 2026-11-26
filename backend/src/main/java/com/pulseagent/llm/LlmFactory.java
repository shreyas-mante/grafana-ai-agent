package com.pulseagent.llm;

import com.pulseagent.config.AppProperties;
import com.pulseagent.tenant.SettingsService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the AI provider a workspace configured (bring your own key). Only the bootstrap
 * workspace may fall back to the operator's environment key, so one tenant can never spend
 * another's quota.
 */
@Component
public class LlmFactory {

    private record Cached(String fingerprint, Llm.Provider provider) {}

    private final SettingsService settings;
    private final AppProperties props;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LlmFactory(SettingsService settings, AppProperties props) {
        this.settings = settings;
        this.props = props;
    }

    public Optional<Llm.Provider> forWorkspace(String ws) {
        String fp = settings.fingerprint(ws, "llm.provider", "llm.model", "llm.apiKey", "llm.baseUrl");
        Cached c = cache.get(ws);
        if (c != null && c.fingerprint().equals(fp)) return Optional.ofNullable(c.provider());
        Llm.Provider p = build(ws);
        cache.put(ws, new Cached(fp, p));
        return Optional.ofNullable(p);
    }

    /** The Gemini client for embeddings, when this workspace uses Gemini. */
    public Optional<GeminiClient> geminiFor(String ws) {
        return forWorkspace(ws).filter(p -> p instanceof GeminiClient).map(p -> (GeminiClient) p);
    }

    private Llm.Provider build(String ws) {
        String provider = settings.getOr(ws, "llm.provider", "none");
        String key = settings.get(ws, "llm.apiKey");
        String model = settings.get(ws, "llm.model");
        return switch (provider) {
            case "gemini" -> key == null || key.isBlank() ? null : new GeminiClient(key, model, props.llm().embeddingModel());
            case "anthropic" -> key == null || key.isBlank() ? null : new AnthropicProvider(key, model);
            case "openai" -> {
                String base = settings.get(ws, "llm.baseUrl");
                boolean local = base != null && settings.allowPrivate(ws, "llm.baseUrl");
                // Local servers such as Ollama need no key; hosted APIs do.
                if ((key == null || key.isBlank()) && !local) yield null;
                yield new OpenAiCompatibleProvider(base, key, model, local);
            }
            default -> null;
        };
    }
}
