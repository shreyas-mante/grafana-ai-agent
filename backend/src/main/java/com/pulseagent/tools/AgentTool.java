package com.pulseagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.BiFunction;

/** A narrow, typed, read-only operation the model may request. */
public record AgentTool(String name, ObjectNode declaration, BiFunction<JsonNode, ToolContext, JsonNode> executor) {}
