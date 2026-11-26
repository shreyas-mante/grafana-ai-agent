package com.pulseagent.net;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Builds outbound REST clients: strict timeouts, no redirects, full value encoding, auth header. */
public final class HttpClients {

    private HttpClients() {}

    public static RestClient build(String baseUrl, String username, String token, int readTimeoutSeconds) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        var uriFactory = new DefaultUriBuilderFactory(baseUrl.replaceAll("/+$", ""));
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        var b = RestClient.builder().requestFactory(factory).uriBuilderFactory(uriFactory)
                .defaultHeader("User-Agent", "pulseagent");
        if (token != null && !token.isBlank()) {
            if (username != null && !username.isBlank()) {
                b.defaultHeader("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((username.trim() + ":" + token.trim()).getBytes(StandardCharsets.UTF_8)));
            } else {
                b.defaultHeader("Authorization", "Bearer " + token.trim());
            }
        }
        return b.build();
    }
}
