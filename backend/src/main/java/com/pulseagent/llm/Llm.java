package com.pulseagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Provider-neutral types for the agent loop. Each provider keeps its own native history. */
public final class Llm {
    private Llm() {}

    public record Param(String name, String type, String description, boolean required) {}

    /** type is one of string, integer, number, boolean. */
    public record ToolSpec(String name, String description, List<Param> params) {}

    public record ToolCall(String id, String name, JsonNode args) {}

    public record ToolResult(String id, String name, JsonNode result, boolean error) {}

    public record Turn(String text, List<ToolCall> calls) {}

    /** One conversation with a model. Not thread-safe; used by one investigation at a time. */
    public interface Session {
        void user(String text);

        void assistant(String text);

        /** Asks the model for its next turn. With allowTools=false the model must answer in text. */
        Turn next(boolean allowTools);

        void toolResults(List<ToolResult> results);
    }

    public interface Provider {
        String name();

        String model();

        Session session(String system, List<ToolSpec> tools);
    }
}
