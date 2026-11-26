package com.pulseagent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.pulseagent.config.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Claude provider using the official Anthropic Java SDK. Assistant turns are replayed with
 * Message.toParam() so thinking and tool_use blocks round-trip unchanged.
 */
public class AnthropicProvider implements Llm.Provider {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private final AnthropicClient client;
    private final String model;

    public AnthropicProvider(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(2)
                .build();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
    }

    @Override public String name() { return "anthropic"; }

    @Override public String model() { return model; }

    @Override
    public Llm.Session session(String system, List<Llm.ToolSpec> specs) {
        List<Tool> tools = specs.stream().map(AnthropicProvider::toTool).toList();
        List<MessageParam> messages = new ArrayList<>();
        return new Llm.Session() {
            @Override public void user(String text) {
                messages.add(MessageParam.ofUser(text));
            }

            @Override public void assistant(String text) {
                messages.add(MessageParam.ofAssistant(text));
            }

            @Override public Llm.Turn next(boolean allowTools) {
                MessageCreateParams.Builder b = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(16000L)
                        .system(system)
                        .messages(messages);
                tools.forEach(b::addTool);
                if (!allowTools && !tools.isEmpty()) b.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
                Message m;
                try {
                    m = client.messages().create(b.build());
                } catch (RateLimitException e) {
                    throw new LlmException("Claude's rate limit was hit. Wait a minute and try again.");
                } catch (AnthropicServiceException e) {
                    log.warn("Claude call failed with HTTP {}", e.statusCode());
                    throw new LlmException("Claude returned HTTP " + e.statusCode() + (e.statusCode() == 401 ? ": check the API key" : ""));
                } catch (Exception e) {
                    throw new LlmException("Could not reach Claude");
                }
                if (m.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
                    throw new LlmException("Claude declined this request (safety refusal).");
                }
                messages.add(m.toParam());
                StringBuilder text = new StringBuilder();
                List<Llm.ToolCall> calls = new ArrayList<>();
                for (ContentBlock block : m.content()) {
                    block.text().ifPresent(t -> text.append(t.text()));
                    block.toolUse().ifPresent(tu -> calls.add(new Llm.ToolCall(tu.id(), tu.name(), toJson(tu._input()))));
                }
                return new Llm.Turn(text.toString(), calls);
            }

            @Override public void toolResults(List<Llm.ToolResult> results) {
                List<ContentBlockParam> blocks = results.stream().map(r -> ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(r.id())
                                .content(JsonUtil.write(r.result()))
                                .isError(r.error())
                                .build())).toList();
                messages.add(MessageParam.ofUserOfContentBlockParams(blocks));
            }
        };
    }

    private static Tool toTool(Llm.ToolSpec t) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        List<String> required = new ArrayList<>();
        for (Llm.Param p : t.params()) {
            props.putAdditionalProperty(p.name(), JsonValue.from(Map.of("type", p.type(), "description", p.description())));
            if (p.required()) required.add(p.name());
        }
        return Tool.builder()
                .name(t.name())
                .description(t.description())
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build())
                .build();
    }

    private static com.fasterxml.jackson.databind.JsonNode toJson(JsonValue v) {
        try {
            return JsonUtil.M.valueToTree(v.convert(Object.class));
        } catch (Exception e) {
            return JsonUtil.obj();
        }
    }
}
