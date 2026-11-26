package com.pulseagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All configuration comes from environment variables. Secrets are never logged
 * and toString() is overridden on records that hold them.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Llm llm, Prometheus prometheus, Code code,
                            Knowledge knowledge, Limits limits) {

    public record Security(String adminKeySha256, String ingestKeySha256, String sessionSecret,
                           boolean secureCookies, int sessionHours) {
        @Override public String toString() { return "Security[redacted]"; }
    }

    public record Llm(String apiKey, String model, String embeddingModel, String baseUrl,
                      int maxSteps, int timeoutSeconds) {
        public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
        @Override public String toString() { return "Llm[model=" + model + ", key=" + (configured() ? "set" : "unset") + "]"; }
    }

    public record Prometheus(String url, String bearerToken) {
        @Override public String toString() { return "Prometheus[url=" + url + "]"; }
    }

    public record Code(String localRepoPath, String githubRepo, String githubToken, String githubApiUrl,
                       List<String> appPackages) {
        public boolean githubConfigured() {
            return githubRepo != null && !githubRepo.isBlank() && githubToken != null && !githubToken.isBlank();
        }
        public boolean localConfigured() { return localRepoPath != null && !localRepoPath.isBlank(); }
        @Override public String toString() { return "Code[repo=" + githubRepo + ", local=" + localRepoPath + "]"; }
    }

    public record Knowledge(String dir, String dataDir) {}

    public record Limits(int maxRangeMinutes, int minStepSeconds, int maxSeries, long maxBodyBytes,
                         int requestsPerMinute, int loginPerMinute, int aiPerMinute, int ingestPerMinute) {}
}
