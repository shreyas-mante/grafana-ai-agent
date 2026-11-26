package com.pulseagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Gemini REST client (generateContent with function calling, and batch embeddings).
 * The API key is sent only in the x-goog-api-key header, never in URLs or logs.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private final AppProperties.Llm cfg;
    private final RestClient rest;

    public GeminiClient(AppProperties props) {
        this.cfg = props.llm();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(30, cfg.timeoutSeconds())));
        this.rest = RestClient.builder().requestFactory(factory).baseUrl(cfg.baseUrl()).build();
    }

    public boolean available() { return cfg.configured(); }

    public String model() { return cfg.model(); }

    /** Sends a conversation and returns the model's content node ({role, parts[]}). */
    public JsonNode generate(String system, ArrayNode contents, ArrayNode functionDeclarations) {
        ObjectNode body = JsonUtil.obj();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        body.set("contents", contents);
        if (functionDeclarations != null && !functionDeclarations.isEmpty()) {
            body.putArray("tools").addObject().set("functionDeclarations", functionDeclarations);
        }
        body.putObject("generationConfig").put("temperature", 0.2).put("maxOutputTokens", 8192);
        JsonNode res = post("/models/" + cfg.model() + ":generateContent", body);
        JsonNode content = res.path("candidates").path(0).path("content");
        if (content.isMissingNode() || !content.has("parts")) {
            String reason = res.path("candidates").path(0).path("finishReason").asText(res.path("promptFeedback").path("blockReason").asText("unknown"));
            throw new LlmException("The AI returned no content (reason: " + reason + ")");
        }
        return content;
    }

    /** Embeds texts in batches of up to 100. taskType is RETRIEVAL_DOCUMENT or RETRIEVAL_QUERY. */
    public List<float[]> embed(List<String> texts, String taskType) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += 100) {
            ObjectNode body = JsonUtil.obj();
            ArrayNode reqs = body.putArray("requests");
            for (String t : texts.subList(i, Math.min(texts.size(), i + 100))) {
                ObjectNode r = reqs.addObject();
                r.put("model", "models/" + cfg.embeddingModel());
                r.putObject("content").putArray("parts").addObject().put("text", t);
                r.put("taskType", taskType);
                r.put("outputDimensionality", 768);
            }
            JsonNode res = post("/models/" + cfg.embeddingModel() + ":batchEmbedContents", body);
            for (JsonNode e : res.path("embeddings")) {
                JsonNode values = e.path("values");
                float[] v = new float[values.size()];
                for (int k = 0; k < v.length; k++) v[k] = (float) values.get(k).asDouble();
                out.add(v);
            }
        }
        if (out.size() != texts.size()) throw new LlmException("Embedding count mismatch");
        return out;
    }

    private JsonNode post(String path, ObjectNode body) {
        if (!available()) throw new LlmException("GEMINI_API_KEY is not configured");
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return rest.post().uri(path)
                        .header("x-goog-api-key", cfg.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = status == 429 || status == 500 || status == 502 || status == 503;
                if (retryable && attempts < 4) {
                    sleep(1500L * attempts * attempts);
                    continue;
                }
                // Log the status only: response bodies can echo request content.
                log.warn("Gemini call failed with HTTP {}", status);
                throw new LlmException(status == 429
                        ? "The AI provider's free-tier rate limit was hit. Wait a minute and try again."
                        : "The AI provider returned HTTP " + status);
            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                if (attempts < 3) {
                    sleep(1000L * attempts);
                    continue;
                }
                log.warn("Gemini call failed: {}", e.getClass().getSimpleName());
                throw new LlmException("Could not reach the AI provider");
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted");
        }
    }
}
