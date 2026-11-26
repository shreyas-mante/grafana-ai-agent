package com.pulseagent.sdk;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pulseagent.enabled=true
 * pulseagent.endpoint=https://your-pulseagent-host
 * pulseagent.ingest-key=${PULSEAGENT_INGEST_KEY}   (use an env var, never commit the key)
 * pulseagent.service=payment-service
 */
@ConfigurationProperties(prefix = "pulseagent")
public class PulseAgentProperties {
    private boolean enabled = false;
    private String endpoint;
    private String ingestKey;
    private String service = "app";
    private String environment = "production";
    private String commitSha;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getIngestKey() { return ingestKey; }
    public void setIngestKey(String ingestKey) { this.ingestKey = ingestKey; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    @Override
    public String toString() {
        return "PulseAgentProperties[endpoint=" + endpoint + ", service=" + service + ", key=" + (ingestKey == null ? "unset" : "set") + "]";
    }
}
