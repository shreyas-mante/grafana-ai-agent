package com.pulseagent.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.code.CodeIntel;
import com.pulseagent.integrations.GrafanaClient;
import com.pulseagent.integrations.LokiClient;
import com.pulseagent.integrations.PrometheusClient;
import com.pulseagent.integrations.TempoClient;
import com.pulseagent.llm.Llm;

import java.util.List;
import java.util.Optional;

/** Everything one workspace has configured, resolved for a single investigation or request. */
public record WorkspaceEnv(
        String workspaceId,
        Optional<Llm.Provider> llm,
        Optional<PrometheusClient> prometheus,
        Optional<LokiClient> loki,
        Optional<TempoClient> tempo,
        Optional<GrafanaClient> grafana,
        Optional<CodeIntel> code,
        List<String> appPackages,
        JsonNode topology,
        boolean actionsEnabled,
        List<String> problems) {

    public String codeProviderName() {
        return code.map(CodeIntel::providerName).orElse("none");
    }

    public boolean hasTopology() {
        return topology != null && topology.path("services").isArray() && !topology.path("services").isEmpty();
    }
}
