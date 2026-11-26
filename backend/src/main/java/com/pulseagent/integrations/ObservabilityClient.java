package com.pulseagent.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.net.HttpClients;
import com.pulseagent.net.OutboundGuard;
import com.pulseagent.tools.ToolException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Shared, read-only HTTP access to an observability backend with SSRF re-checks on each call. */
public abstract class ObservabilityClient {

    protected final String baseUrl;
    private final boolean allowPrivate;
    private final RestClient rest;
    private final String label;

    protected ObservabilityClient(String label, String baseUrl, String username, String token, boolean allowPrivate) {
        this.label = label;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.allowPrivate = allowPrivate;
        OutboundGuard.check(this.baseUrl, allowPrivate);
        this.rest = HttpClients.build(this.baseUrl, username, token, 20);
    }

    public String host() {
        return java.net.URI.create(baseUrl).getHost();
    }

    protected JsonNode get(String uri, Object... vars) {
        try {
            OutboundGuard.check(baseUrl, allowPrivate);
        } catch (IllegalArgumentException e) {
            throw new ToolException(label + " URL is not allowed: " + e.getMessage());
        }
        try {
            JsonNode res = rest.get().uri(uri, vars).retrieve().body(JsonNode.class);
            if (res == null) throw new ToolException(label + " returned an empty response");
            return res;
        } catch (RestClientResponseException e) {
            String msg = "";
            try {
                JsonNode b = e.getResponseBodyAs(JsonNode.class);
                msg = b == null ? "" : b.path("error").asText(b.path("message").asText(""));
            } catch (Exception ignored) {
                // not JSON
            }
            throw new ToolException(label + " HTTP " + e.getStatusCode().value() + (msg.isEmpty() ? "" : ": " + (msg.length() > 300 ? msg.substring(0, 300) : msg)));
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException(label + " is unreachable");
        }
    }
}
