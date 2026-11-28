package com.pulseagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Gemini REST provider (function calling + embeddings). The API key is sent only in the
 * x-goog-api-key header, never in URLs or logs.
 */
public class GeminiClient implements Llm.Provider {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_MODEL = "gemini-flash-latest";
    private final String apiKey;
    private final String model;
    private final String embeddingModel;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(90);
    /** HTTP/1.1 with fixed-length bodies: streamed bodies over HTTP/2 could stall until the deadline. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GeminiClient(String apiKey, String model, String embeddingModel) {
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.embeddingModel = embeddingModel == null || embeddingModel.isBlank() ? "gemini-embedding-001" : embeddingModel;
    }

    @Override public String name() { return "gemini"; }

    @Override public String model() { return model; }

    @Override
    public Llm.Session session(String system, List<Llm.ToolSpec> tools) {
        ArrayNode decls = JsonUtil.arr();
        for (Llm.ToolSpec t : tools) {
            ObjectNode d = decls.addObject();
            d.put("name", t.name()).put("description", t.description());
            if (!t.params().isEmpty()) {
                ObjectNode schema = d.putObject("parameters");
                schema.put("type", "OBJECT");
                ObjectNode props = schema.putObject("properties");
                ArrayNode req = JsonUtil.arr();
                for (Llm.Param p : t.params()) {
                    props.putObject(p.name()).put("type", p.type().toUpperCase(Locale.ROOT)).put("description", p.description());
                    if (p.required()) req.add(p.name());
                }
                if (!req.isEmpty()) schema.set("required", req);
            }
        }
        ArrayNode contents = JsonUtil.arr();
        return new Llm.Session() {
            @Override public void user(String text) {
                contents.addObject().put("role", "user").putArray("parts").addObject().put("text", text);
            }

            @Override public void assistant(String text) {
                contents.addObject().put("role", "model").putArray("parts").addObject().put("text", text);
            }

            @Override public Llm.Turn next(boolean allowTools) {
                ObjectNode body = JsonUtil.obj();
                body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
                body.set("contents", contents);
                if (!decls.isEmpty()) {
                    body.putArray("tools").addObject().set("functionDeclarations", decls);
                    body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", allowTools ? "AUTO" : "NONE");
                }
                body.putObject("generationConfig").put("temperature", 0.2).put("maxOutputTokens", 8192);
                JsonNode res = post("/models/" + model + ":generateContent", body);
                JsonNode content = res.path("candidates").path(0).path("content");
                if (content.isMissingNode() || !content.has("parts")) {
                    String reason = res.path("candidates").path(0).path("finishReason").asText(res.path("promptFeedback").path("blockReason").asText("unknown"));
                    throw new LlmException("The AI returned no content (reason: " + reason + ")");
                }
                contents.add(content); // replay exactly, including thought signatures
                StringBuilder text = new StringBuilder();
                List<Llm.ToolCall> calls = new ArrayList<>();
                int i = 0;
                for (JsonNode part : content.path("parts")) {
                    if (part.has("functionCall")) {
                        JsonNode c = part.path("functionCall");
                        calls.add(new Llm.ToolCall("call_" + i++, c.path("name").asText(), c.path("args")));
                    } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                        text.append(part.path("text").asText());
                    }
                }
                return new Llm.Turn(text.toString(), calls);
            }

            @Override public void toolResults(List<Llm.ToolResult> results) {
                ObjectNode turn = contents.addObject();
                turn.put("role", "user");
                ArrayNode parts = turn.putArray("parts");
                for (Llm.ToolResult r : results) {
                    ObjectNode fr = parts.addObject().putObject("functionResponse");
                    fr.put("name", r.name());
                    fr.putObject("response").set("result", r.result());
                }
            }
        };
    }

    /** Embeds texts in batches of up to 100. taskType is RETRIEVAL_DOCUMENT or RETRIEVAL_QUERY. */
    public List<float[]> embed(List<String> texts, String taskType) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += 100) {
            ObjectNode body = JsonUtil.obj();
            ArrayNode reqs = body.putArray("requests");
            for (String t : texts.subList(i, Math.min(texts.size(), i + 100))) {
                ObjectNode r = reqs.addObject();
                r.put("model", "models/" + embeddingModel);
                r.putObject("content").putArray("parts").addObject().put("text", t);
                r.put("taskType", taskType);
                r.put("outputDimensionality", 768);
            }
            JsonNode res = post("/models/" + embeddingModel + ":batchEmbedContents", body);
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
        byte[] payload = JsonUtil.write(body).getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(CALL_TIMEOUT)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        int attempts = 0;
        while (true) {
            attempts++;
            long t0 = System.nanoTime();
            int status;
            String text;
            try {
                // Hard deadline on the whole exchange, response body included.
                HttpResponse<String> res = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                        .get(CALL_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
                status = res.statusCode();
                text = res.body();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted");
            } catch (Exception e) {
                log.warn("Gemini call attempt {} failed after {} ms: {}", attempts, elapsedMs(t0), e.getClass().getSimpleName());
                if (attempts < 2) {
                    sleep(1000L);
                    continue;
                }
                throw new LlmException("Could not reach the AI provider (timed out or connection failed)");
            }
            if (status >= 200 && status < 300) {
                try {
                    return JsonUtil.M.readTree(text);
                } catch (Exception e) {
                    throw new LlmException("The AI provider returned an unreadable response");
                }
            }
            if ((status == 429 || status >= 500) && attempts < 3) {
                log.warn("Gemini call attempt {} returned HTTP {} after {} ms, retrying", attempts, status, elapsedMs(t0));
                sleep(2000L * attempts);
                continue;
            }
            log.warn("Gemini call failed with HTTP {} after {} ms", status, elapsedMs(t0));
            throw new LlmException(switch (status) {
                case 429 -> "The AI provider's rate limit or free quota was hit. Wait a minute and try again.";
                case 400, 401, 403 -> "The AI provider rejected the request (HTTP " + status + "). Check the API key and model name.";
                case 404 -> "The model was not found (HTTP 404). Try " + DEFAULT_MODEL + ".";
                default -> "The AI provider returned HTTP " + status;
            });
        }
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted");
        }
    }
}
