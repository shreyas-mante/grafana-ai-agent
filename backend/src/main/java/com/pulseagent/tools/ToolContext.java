package com.pulseagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.code.CodeModels.CodeContext;
import com.pulseagent.rag.KnowledgeBase;

import java.util.List;

/**
 * Where tools record verified artefacts (charts, code locations, knowledge hits). The UI shows
 * these directly, so facts like file, line, author and commit never depend on the model's wording.
 */
public interface ToolContext {
    String workspaceId();

    String investigationId();

    void attachChart(String title, String query, JsonNode points, JsonNode summary);

    void attachCode(String key, CodeContext ctx);

    void attachKnowledge(List<KnowledgeBase.Hit> hits);

    void attachAction(String actionId);
}
