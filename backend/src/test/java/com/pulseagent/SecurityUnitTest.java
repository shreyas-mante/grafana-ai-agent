package com.pulseagent;

import com.pulseagent.config.AppProperties;
import com.pulseagent.net.OutboundGuard;
import com.pulseagent.security.Redactor;
import com.pulseagent.security.SessionTokenService;
import com.pulseagent.tenant.Crypto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUnitTest {

    static AppProperties props(String secret, String encKey) {
        return new AppProperties(
                new AppProperties.Platform("", "http://localhost", "invite", encKey, false),
                new AppProperties.Security("", "", secret, true, 8),
                new AppProperties.Llm("", "m", "e", "http://x", 5, 30),
                new AppProperties.Prometheus("http://localhost:9090", "", ""),
                new AppProperties.Code("", "", "", "https://api.github.com", List.of("com.demo")),
                new AppProperties.Knowledge("../knowledge", "./target/test-data"),
                new AppProperties.Limits(1440, 5, 10, 262144, 240, 10, 15, 1200));
    }

    @Test
    void sessionTokensAreSignedAndRevocable() {
        SessionTokenService s = new SessionTokenService(props("a-very-long-session-secret-for-tests-123456", null));
        String t = s.issue(Map.of("kind", "user", "sub", "usr_1", "ws", "ws_1", "name", "a@b.co"));
        assertThat(s.verify(t)).containsEntry("sub", "usr_1").containsEntry("ws", "ws_1");
        String tampered = t.substring(0, t.length() - 2) + (t.endsWith("A") ? "BB" : "AA");
        assertThat(s.verify(tampered)).isNull();
        assertThat(new SessionTokenService(props("another-secret-that-is-long-enough-000000", null)).verify(t)).isNull();
        s.revoke(t);
        assertThat(s.verify(t)).isNull();
        assertThat(s.verify("garbage")).isNull();
    }

    @Test
    void secretsAreEncryptedWithAuthenticatedEncryption() {
        Crypto c = new Crypto(props("x".repeat(40), "encryption-key-for-tests-0123456789abcdef"));
        String enc = c.encrypt("ghp_supersecret");
        assertThat(enc).startsWith("v1:").doesNotContain("supersecret");
        assertThat(c.decrypt(enc)).isEqualTo("ghp_supersecret");
        assertThat(c.encrypt("same")).isNotEqualTo(c.encrypt("same")); // random IV
        String tampered = enc.substring(0, enc.length() - 4) + "AAAA";
        assertThat(c.decrypt(tampered)).isNull();
        assertThat(new Crypto(props("x".repeat(40), "a-different-encryption-key-000000000000")).decrypt(enc)).isNull();
    }

    @Test
    void outboundGuardBlocksSsrfTargets() {
        for (String bad : List.of("http://example.com", "https://localhost:9090", "https://127.0.0.1", "https://169.254.169.254/latest",
                "https://10.0.0.5", "https://192.168.1.1", "https://[::1]", "https://metadata.google.internal", "https://user:pass@example.com",
                "ftp://example.com", "https://example.com/?x=1", "https://0.0.0.0")) {
            assertThatThrownBy(() -> OutboundGuard.check(bad, false)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(OutboundGuard.check("http://localhost:9090", true).getHost()).isEqualTo("localhost");
    }

    @Test
    void redactorRemovesSecretsAndPersonalData() {
        String in = "password=hunter2 Authorization: Bearer abcdefghijklmnop token: xyz123456 "
                + "AKIAABCDEFGHIJKLMNOP AIzaSyA1234567890abcdefghijklmnopqrstuvw ghp_abcdefghijklmnopqrstuvwxyz12 "
                + "mail me at jane.doe@example.com card 4111 1111 1111 1111 pa_admin_abcdefghijklmnopqrstuvwxyz";
        String out = Redactor.redact(in);
        assertThat(out).doesNotContain("hunter2", "abcdefghijklmnop", "xyz123456", "AKIAABCDEFGHIJKLMNOP",
                "AIzaSyA1234567890", "ghp_abcdefghijklmnopqrstuvwxyz12", "jane.doe@example.com", "4111 1111", "pa_admin_abcdef");
        assertThat(Redactor.redact("Connection is not available, request timed out after 500ms"))
                .isEqualTo("Connection is not available, request timed out after 500ms");
    }
}
