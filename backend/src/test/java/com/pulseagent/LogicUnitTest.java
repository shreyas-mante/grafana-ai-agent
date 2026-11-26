package com.pulseagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pulseagent.config.JsonUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogicUnitTest {

    @Test
    void summaryComparesBaselineWithRecent() throws Exception {
        Method m = Class.forName("com.pulseagent.tools.ToolCatalog").getDeclaredMethod("summarize", List.class);
        m.setAccessible(true);
        List<double[]> pts = new ArrayList<>();
        // Baseline = first 60% of points, recent = last 25%.
        for (int i = 0; i < 70; i++) pts.add(new double[]{i, 10});
        for (int i = 70; i < 100; i++) pts.add(new double[]{i, 50});
        ObjectNode s = (ObjectNode) m.invoke(null, pts);
        assertThat(s.path("baselineMean").asDouble()).isEqualTo(10.0);
        assertThat(s.path("recentMean").asDouble()).isEqualTo(50.0);
        assertThat(s.path("changePercent").asDouble()).isEqualTo(400.0);
        assertThat(s.path("max").asDouble()).isEqualTo(50.0);
    }

    @Test
    void extractsJsonFromModelText() {
        assertThat(JsonUtil.extractObject("Here you go:\n```json\n{\"title\":\"x\",\"n\":1}\n```").path("title").asText()).isEqualTo("x");
        assertThat(JsonUtil.extractObject("no json here")).isNull();
    }

    @Test
    void chunksSplitOnHeadings() throws Exception {
        Method m = Class.forName("com.pulseagent.rag.KnowledgeBase").getDeclaredMethod("chunk", String.class);
        m.setAccessible(true);
        String doc = "# T\n" + "intro ".repeat(50) + "\n## Symptoms\n" + "s ".repeat(150) + "\n## Fix\nDo the thing.";
        @SuppressWarnings("unchecked") List<String> chunks = (List<String>) m.invoke(null, doc);
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks.get(chunks.size() - 1)).contains("## Fix");
    }
}
