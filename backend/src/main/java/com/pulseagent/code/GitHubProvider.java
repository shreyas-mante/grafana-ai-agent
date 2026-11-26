package com.pulseagent.code;

import com.fasterxml.jackson.databind.JsonNode;
import com.pulseagent.code.CodeModels.BlameInfo;
import com.pulseagent.code.CodeModels.CommitInfo;
import com.pulseagent.config.AppProperties;
import com.pulseagent.config.JsonUtil;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads code, blame and linked pull requests through the GitHub API with a read-only token.
 * The token is only ever placed in the Authorization header.
 */
class GitHubProvider implements CodeModels.CodeProvider {

    private final RestClient rest;
    private final String owner;
    private final String name;
    private final Map<String, List<String>> treeCache = new ConcurrentHashMap<>();

    GitHubProvider(AppProperties.Code cfg) {
        if (!cfg.githubRepo().matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalStateException("APP_GITHUB_REPO must look like owner/name");
        }
        String[] parts = cfg.githubRepo().split("/");
        this.owner = parts[0];
        this.name = parts[1];
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(cfg.githubApiUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.githubToken())
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "pulseagent")
                .build();
    }

    @Override public String name() { return "github"; }

    @Override
    public List<String> listFiles(String ref) {
        return treeCache.computeIfAbsent(ref, r -> {
            JsonNode tree = rest.get().uri("/repos/{o}/{n}/git/trees/{ref}?recursive=1", owner, name, r)
                    .retrieve().body(JsonNode.class);
            List<String> files = new ArrayList<>();
            if (tree != null) for (JsonNode t : tree.path("tree")) if ("blob".equals(t.path("type").asText())) files.add(t.path("path").asText());
            return files;
        });
    }

    @Override
    public String fileContent(String ref, String path) {
        try {
            String encoded = UriUtils.encodePath(path, StandardCharsets.UTF_8);
            byte[] raw = rest.get()
                    .uri(b -> b.path("/repos/" + owner + "/" + name + "/contents/").path(encoded).queryParam("ref", ref).build())
                    .accept(MediaType.parseMediaType("application/vnd.github.raw"))
                    .retrieve().body(byte[].class);
            return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Map<Integer, BlameInfo> blameRange(String ref, String path, int start, int end) {
        String query = """
            query($owner:String!, $name:String!, $ref:String!, $path:String!) {
              repository(owner:$owner, name:$name) {
                object(expression:$ref) {
                  ... on Commit {
                    blame(path:$path) {
                      ranges {
                        startingLine endingLine
                        commit {
                          oid messageHeadline committedDate
                          author { name user { login } }
                          associatedPullRequests(first:1) { nodes { number title url } }
                        }
                      }
                    }
                  }
                }
              }
            }""";
        var body = JsonUtil.obj();
        body.put("query", query);
        body.putObject("variables").put("owner", owner).put("name", name).put("ref", ref).put("path", path);
        JsonNode res = rest.post().uri("/graphql").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        Map<Integer, BlameInfo> result = new HashMap<>();
        if (res == null) return result;
        for (JsonNode r : res.path("data").path("repository").path("object").path("blame").path("ranges")) {
            int from = Math.max(start, r.path("startingLine").asInt());
            int to = Math.min(end, r.path("endingLine").asInt());
            if (from > to) continue;
            JsonNode c = r.path("commit");
            JsonNode pr = c.path("associatedPullRequests").path("nodes").path(0);
            String oid = c.path("oid").asText();
            BlameInfo b = new BlameInfo(oid, oid.length() >= 7 ? oid.substring(0, 7) : oid,
                    c.path("author").path("name").asText(null),
                    c.path("author").path("user").path("login").asText(null),
                    c.path("committedDate").asText(null),
                    c.path("messageHeadline").asText(null),
                    pr.isMissingNode() ? null : pr.path("number").asInt(),
                    pr.isMissingNode() ? null : pr.path("title").asText(null),
                    pr.isMissingNode() ? null : pr.path("url").asText(null));
            for (int i = from; i <= to; i++) result.put(i, b);
        }
        return result;
    }

    @Override
    public List<CommitInfo> recentCommits(int hours) {
        String since = Instant.now().minus(Duration.ofHours(Math.max(1, Math.min(hours, 720)))).toString();
        JsonNode res = rest.get().uri("/repos/{o}/{n}/commits?per_page=20&since={s}", owner, name, since)
                .retrieve().body(JsonNode.class);
        List<CommitInfo> list = new ArrayList<>();
        if (res != null) for (JsonNode c : res) {
            list.add(new CommitInfo(c.path("sha").asText().substring(0, 7),
                    c.path("author").path("login").asText(c.path("commit").path("author").path("name").asText()),
                    c.path("commit").path("author").path("date").asText(),
                    c.path("commit").path("message").asText().lines().findFirst().orElse("")));
        }
        return list;
    }
}
