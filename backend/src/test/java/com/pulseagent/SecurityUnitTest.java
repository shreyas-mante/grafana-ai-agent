package com.pulseagent;

import com.pulseagent.config.AppProperties;
import com.pulseagent.security.ApiKeyService;
import com.pulseagent.security.Redactor;
import com.pulseagent.security.SessionTokenService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityUnitTest {

    static final String ADMIN = "pa_admin_test_0123456789abcdefghij";
    static final String INGEST = "pa_ingest_test_0123456789abcdefghij";

    static AppProperties props(String secret) {
        return new AppProperties(
                new AppProperties.Security(ApiKeyService.sha256Hex(ADMIN), ApiKeyService.sha256Hex(INGEST), secret, true, 8),
                new AppProperties.Llm("", "m", "e", "http://x", 5, 30),
                new AppProperties.Prometheus("http://localhost:9090", ""),
                new AppProperties.Code("", "", "", "https://api.github.com", List.of("com.demo")),
                new AppProperties.Knowledge("../knowledge", "./.data"),
                new AppProperties.Limits(1440, 5, 10, 262144, 240, 10, 15, 1200));
    }

    @Test
    void apiKeysMatchOnlyTheirOwnRole() {
        ApiKeyService keys = new ApiKeyService(props(null));
        assertThat(keys.authenticate(ADMIN)).contains(ApiKeyService.Role.ADMIN);
        assertThat(keys.authenticate(INGEST)).contains(ApiKeyService.Role.INGEST);
        assertThat(keys.authenticate(ADMIN + "x")).isEmpty();
        assertThat(keys.authenticate("short")).isEmpty();
        assertThat(keys.authenticate(null)).isEmpty();
    }

    @Test
    void sessionTokensAreSignedAndRevocable() {
        SessionTokenService s = new SessionTokenService(props("a-very-long-session-secret-for-tests-123456"));
        String t = s.issue("admin");
        assertThat(s.verify(t)).isEqualTo("admin");

        String tampered = t.substring(0, t.length() - 2) + (t.endsWith("A") ? "BB" : "AA");
        assertThat(s.verify(tampered)).isNull();

        SessionTokenService other = new SessionTokenService(props("another-secret-that-is-long-enough-000000"));
        assertThat(other.verify(t)).isNull();

        s.revoke(t);
        assertThat(s.verify(t)).isNull();
        assertThat(s.verify("garbage")).isNull();
    }

    @Test
    void redactorRemovesSecretsAndPersonalData() {
        String in = "password=hunter2 Authorization: Bearer abcdefghijklmnop token: xyz123456 "
                + "AKIAABCDEFGHIJKLMNOP AIzaSyA1234567890abcdefghijklmnopqrstuvw ghp_abcdefghijklmnopqrstuvwxyz12 "
                + "mail me at jane.doe@example.com card 4111 1111 1111 1111 " + ADMIN;
        String out = Redactor.redact(in);
        assertThat(out).doesNotContain("hunter2", "abcdefghijklmnop", "xyz123456", "AKIAABCDEFGHIJKLMNOP",
                "AIzaSyA1234567890", "ghp_abcdefghijklmnopqrstuvwxyz12", "jane.doe@example.com", "4111 1111", ADMIN);
        assertThat(Redactor.redact("Connection is not available, request timed out after 500ms"))
                .isEqualTo("Connection is not available, request timed out after 500ms");
    }
}
