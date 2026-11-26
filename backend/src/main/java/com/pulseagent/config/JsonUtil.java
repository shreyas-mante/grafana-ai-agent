package com.pulseagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared, thread-safe ObjectMapper helpers. */
public final class JsonUtil {
    public static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    private JsonUtil() {}

    public static ObjectNode obj() { return M.createObjectNode(); }

    public static ArrayNode arr() { return M.createArrayNode(); }

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Parses the first JSON object found in free text (LLMs sometimes wrap JSON in prose or fences). */
    public static ObjectNode extractObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode n = M.readTree(text.substring(start, end + 1));
            return n instanceof ObjectNode o ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated]";
    }
}
