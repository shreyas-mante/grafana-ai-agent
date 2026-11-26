package com.pulseagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import com.pulseagent.net.HttpClients;
import com.pulseagent.net.OutboundGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat Completions-compatible provider: OpenAI, Groq (free tier), OpenRouter, local Ollama, etc.
 * The base URL is SSRF-checked before every request.
 */
public class OpenAiCompatibleProvider implements Llm.Provider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private final String baseUrl;
    private final String model;
    private final boolean allowPrivate;
    private final RestClient rest;

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, String model, boolean allowPrivate) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.replaceAll("/+$", "");
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model;
        this.allowPrivate = allowPrivate;
        OutboundGuard.check(this.baseUrl, allowPrivate);
        this.rest = HttpClients.build(this.baseUrl, null, apiKey, 180);
    }

    @Override public String name() { return "openai"; }

    @Override public String model() { return model; }

    @Override
    public Llm.Session session(String system, List<Llm.ToolSpec> specs) {
        ArrayNode tools = JsonUtil.arr();
        for (Llm.ToolSpec t : specs) {
            ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
            fn.put("name", t.name()).put("description", t.description());
            ObjectNode params = fn.putObject("parameters");
            params.put("type", "object");
            ObjectNode props = params.putObject("properties");
            ArrayNode req = params.putArray("required");
            for (Llm.Param p : t.params()) {
                props.putObject(p.name()).put("type", p.type()).put("description", p.description());
                if (p.required()) req.add(p.name());
            }
        }
        ArrayNode messages = JsonUtil.arr();
        messages.addObject().put("role", "system").put("content", system);
        return new Llm.Session() {
            @Override public void user(String text) {
                messages.addObject().put("role", "user").put("content", text);
            }

            @Override public void assistant(String text) {
                messages.addObject().put("role", "assistant").put("content", text);
            }

            @Override public Llm.Turn next(boolean allowTools) {
                OutboundGuard.check(baseUrl, allowPrivate);
                ObjectNode body = JsonUtil.obj();
                body.put("model", model);
                body.set("messages", messages);
                if (!tools.isEmpty()) {
                    body.set("tools", tools);
                    body.put("tool_choice", allowTools ? "auto" : "none");
                }
                JsonNode res = post(body);
                JsonNode msg = res.path("choices").path(0).path("message");
                if (msg.isMissingNode()) throw new LlmException("The AI returned no message");
                ObjectNode replay = messages.addObject();
                replay.put("role", "assistant");
                replay.put("content", msg.path("content").asText(""));
                List<Llm.ToolCall> calls = new ArrayList<>();
                if (msg.path("tool_calls").isArray() && !msg.path("tool_calls").isEmpty()) {
                    replay.set("tool_calls", msg.path("tool_calls"));
                    for (JsonNode tc : msg.path("tool_calls")) {
                        JsonNode args;
                        try {
                            args = JsonUtil.M.readTree(tc.path("function").path("arguments").asText("{}"));
                        } catch (Exception e) {
                            args = JsonUtil.obj();
                        }
                        calls.add(new Llm.ToolCall(tc.path("id").asText(), tc.path("function").path("name").asText(), args));
                    }
                }
                return new Llm.Turn(msg.path("content").asText(""), calls);
            }

            @Override public void toolResults(List<Llm.ToolResult> results) {
                for (Llm.ToolResult r : results) {
                    messages.addObject().put("role", "tool").put("tool_call_id", r.id()).put("content", JsonUtil.write(r.result()));
                }
            }
        };
    }

    private JsonNode post(ObjectNode body) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return rest.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status >= 500) && attempts < 4) {
                    GeminiClient.sleep(1500L * attempts * attempts);
                    continue;
                }
                log.warn("OpenAI-compatible call failed with HTTP {}", status);
                throw new LlmException(status == 429 ? "The AI provider's rate limit was hit. Wait a minute and try again."
                        : "The AI provider returned HTTP " + status);
            } catch (Exception e) {
                if (attempts < 3) {
                    GeminiClient.sleep(1000L * attempts);
                    continue;
                }
                throw new LlmException("Could not reach the AI provider");
            }
        }
    }
}
