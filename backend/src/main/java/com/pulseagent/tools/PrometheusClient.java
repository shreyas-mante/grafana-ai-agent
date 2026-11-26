package com.pulseagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.config.AppProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** Read-only Prometheus HTTP API client with strict timeouts. */
@Component
public class PrometheusClient {

    private final RestClient rest;
    private final String baseUrl;

    public PrometheusClient(AppProperties props) {
        this.baseUrl = props.prometheus().url();
        URI u = URI.create(baseUrl);
        if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) {
            throw new IllegalStateException("APP_PROMETHEUS_URL must be http or https");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        var uriFactory = new DefaultUriBuilderFactory(baseUrl);
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        var b = RestClient.builder().requestFactory(factory).uriBuilderFactory(uriFactory);
        String token = props.prometheus().bearerToken();
        if (token != null && !token.isBlank()) b.defaultHeader("Authorization", "Bearer " + token);
        this.rest = b.build();
    }

    public String host() {
        return URI.create(baseUrl).getHost();
    }

    public JsonNode queryRange(String query, long start, long end, int step) {
        return call("/api/v1/query_range?query={q}&start={s}&end={e}&step={st}", query, start, end, step);
    }

    public JsonNode metricNames() {
        return call("/api/v1/label/__name__/values");
    }

    public JsonNode alerts() {
        return call("/api/v1/alerts");
    }

    private JsonNode call(String uri, Object... vars) {
        try {
            JsonNode res = rest.get().uri(uri, vars).retrieve().body(JsonNode.class);
            if (res == null || !"success".equals(res.path("status").asText())) {
                throw new ToolException("Prometheus returned an unsuccessful response");
            }
            return res.path("data");
        } catch (RestClientResponseException e) {
            String err = "";
            try {
                err = e.getResponseBodyAs(JsonNode.class).path("error").asText("");
            } catch (Exception ignored) {
                // body not JSON
            }
            throw new ToolException("Prometheus HTTP " + e.getStatusCode().value() + (err.isEmpty() ? "" : ": " + (err.length() > 300 ? err.substring(0, 300) : err)));
        } catch (ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolException("Prometheus is unreachable");
        }
    }
}
