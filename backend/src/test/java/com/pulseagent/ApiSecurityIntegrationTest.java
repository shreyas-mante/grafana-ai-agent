package com.pulseagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiSecurityIntegrationTest {

    static final ObjectMapper M = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String dir = "target/test-data-" + UUID.randomUUID();
        r.add("app.knowledge.data-dir", () -> dir);
        r.add("app.knowledge.dir", () -> "../knowledge");
        r.add("app.platform.signup", () -> "open");
        r.add("app.platform.encryption-key", () -> "integration-test-encryption-key-0123456789");
        r.add("app.security.session-secret", () -> "integration-test-session-secret-0123456789");
        r.add("app.security.admin-key-sha256", () -> "");
        r.add("app.security.ingest-key-sha256", () -> "");
        r.add("app.llm.api-key", () -> "");
        r.add("app.limits.login-per-minute", () -> "1000");
        r.add("app.limits.ai-per-minute", () -> "1000");
    }

    @Autowired MockMvc mvc;

    Cookie alice;
    Cookie bob;
    String aliceAdminKey;
    String aliceIngestKey;

    @BeforeAll
    void setUp() throws Exception {
        alice = signup("alice@example.com", "Alice Corp");
        bob = signup("bob@example.com", "Bob Corp");
        aliceAdminKey = createKey(alice, "{\"name\":\"ci\",\"scope\":\"admin\"}");
        aliceIngestKey = createKey(alice, "{\"name\":\"app\",\"scope\":\"ingest\"}");
    }

    Cookie signup(String email, String ws) throws Exception {
        var res = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\",\"workspaceName\":\"" + ws + "\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("pa_session", true))
                .andExpect(cookie().secure("pa_session", true))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();
        return res.getResponse().getCookie("pa_session");
    }

    String createKey(Cookie who, String body) throws Exception {
        var res = mvc.perform(w(post("/api/admin/keys"), who).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return M.readTree(res.getResponse().getContentAsString()).path("key").asText();
    }

    static MockHttpServletRequestBuilder w(MockHttpServletRequestBuilder b, Cookie c) {
        return b.cookie(c).header("X-Requested-With", "pulseagent");
    }

    @Test
    void anonymousIsRejectedAndHealthIsOpen() throws Exception {
        mvc.perform(get("/api/investigations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/investigations").header("X-API-Key", "pa_admin_not_a_real_key_000000000000")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void passwordsAreCheckedAndWeakOnesRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"alice@example.com\",\"password\":\"wrong password!!\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"c@example.com\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"alice@example.com\",\"password\":\"correct horse battery staple\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void workspacesAreIsolated() throws Exception {
        String event = """
                {"service":"payment-service","exceptionClass":"java.lang.IllegalStateException","message":"secret alice error",
                 "frames":[{"className":"com.demo.payment.PaymentService","methodName":"charge","fileName":"PaymentService.java","lineNumber":18}]}""";
        mvc.perform(post("/api/v1/errors").header("X-API-Key", aliceIngestKey).contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isAccepted());
        String aliceErrors = mvc.perform(get("/api/errors").cookie(alice)).andReturn().getResponse().getContentAsString();
        String bobErrors = mvc.perform(get("/api/errors").cookie(bob)).andReturn().getResponse().getContentAsString();
        assertThat(aliceErrors).contains("secret alice error");
        assertThat(bobErrors).doesNotContain("secret alice error");

        var created = mvc.perform(post("/api/v1/investigations").header("X-API-Key", aliceAdminKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"why?\"}")).andExpect(status().isAccepted()).andReturn();
        String id = M.readTree(created.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get("/api/investigations/" + id).cookie(alice)).andExpect(status().isOk());
        mvc.perform(get("/api/investigations/" + id).cookie(bob)).andExpect(status().isNotFound());

        // Bob can't switch into Alice's workspace.
        JsonNode me = M.readTree(mvc.perform(get("/api/auth/me").cookie(alice)).andReturn().getResponse().getContentAsString());
        mvc.perform(w(post("/api/auth/switch"), bob).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":\"" + me.path("workspaceId").asText() + "\"}")).andExpect(status().isForbidden());
    }

    @Test
    void ingestKeyIsLeastPrivilege() throws Exception {
        mvc.perform(get("/api/investigations").header("X-API-Key", aliceIngestKey)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/settings").header("X-API-Key", aliceIngestKey)).andExpect(status().isForbidden());
    }

    @Test
    void membersCannotAdminister() throws Exception {
        var invite = M.readTree(mvc.perform(w(post("/api/admin/invites"), alice).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("link").asText();
        String token = invite.substring(invite.lastIndexOf('/') + 1);
        var res = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"carol@example.com\",\"password\":\"correct horse battery staple\",\"inviteToken\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie carol = res.getResponse().getCookie("pa_session");
        mvc.perform(get("/api/investigations").cookie(carol)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/settings").cookie(carol)).andExpect(status().isForbidden());
        mvc.perform(w(post("/api/admin/keys"), carol).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"scope\":\"admin\"}"))
                .andExpect(status().isForbidden());
        // Invite links work once.
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"dave@example.com\",\"password\":\"correct horse battery staple\",\"inviteToken\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void secretsAreNeverReturnedAndSsrfIsBlocked() throws Exception {
        mvc.perform(w(put("/api/admin/settings"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"github.token\":\"ghp_topsecretvalue1234567890\",\"github.repo\":\"acme/app\"}}"))
                .andExpect(status().isOk());
        String settings = mvc.perform(get("/api/admin/settings").cookie(alice)).andReturn().getResponse().getContentAsString();
        assertThat(settings).doesNotContain("topsecret").contains("\"set\":true");
        for (String url : new String[]{"http://169.254.169.254/latest/meta-data", "https://127.0.0.1:9090", "https://10.1.2.3"}) {
            mvc.perform(w(put("/api/admin/settings"), alice).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"values\":{\"prometheus.url\":\"" + url + "\"}}")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void cookieWritesNeedTheCsrfHeader() throws Exception {
        mvc.perform(post("/api/investigations").cookie(alice).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void widgetKeysOnlyWorkFromAllowedOriginsAndOnlyOnWidgetRoutes() throws Exception {
        String key = createKey(alice, "{\"name\":\"intranet\",\"scope\":\"widget\",\"allowedOrigins\":[\"https://intranet.example.com\"]}");
        mvc.perform(post("/api/widget/investigations").header("X-Widget-Key", key).header("Origin", "https://evil.example.com")
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/widget/investigations").header("X-Widget-Key", key).header("Origin", "https://intranet.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Is checkout slow?\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://intranet.example.com"));
        mvc.perform(get("/api/investigations").header("X-Widget-Key", key).header("Origin", "https://intranet.example.com"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/investigations").header("X-API-Key", key)).andExpect(status().isUnauthorized());
        mvc.perform(options("/api/widget/investigations").header("Origin", "https://intranet.example.com")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isNoContent());
    }

    @Test
    void revokedKeysStopWorking() throws Exception {
        String key = createKey(alice, "{\"name\":\"temp\",\"scope\":\"admin\"}");
        mvc.perform(get("/api/investigations").header("X-API-Key", key)).andExpect(status().isOk());
        JsonNode keys = M.readTree(mvc.perform(get("/api/admin/keys").cookie(alice)).andReturn().getResponse().getContentAsString());
        String id = null;
        for (JsonNode k : keys) if ("temp".equals(k.path("name").asText())) id = k.path("id").asText();
        mvc.perform(w(delete("/api/admin/keys/" + id), alice)).andExpect(status().isNoContent());
        mvc.perform(get("/api/investigations").header("X-API-Key", key)).andExpect(status().isUnauthorized());
    }

    @Autowired com.pulseagent.actions.ActionService actionService;

    @Test
    void onlyOwnersApproveActionsAndOnlyOnce() throws Exception {
        String ws = M.readTree(mvc.perform(get("/api/auth/me").cookie(alice)).andReturn().getResponse().getContentAsString()).path("workspaceId").asText();
        String id = actionService.propose(ws, null, "webhook", "Roll back abc1234", M.createObjectNode().put("kind", "rollback"), "test");
        // Bob (another workspace) can't see or touch it.
        assertThat(mvc.perform(get("/api/actions").cookie(bob)).andReturn().getResponse().getContentAsString()).doesNotContain(id);
        mvc.perform(w(post("/api/actions/" + id + "/approve"), bob)).andExpect(status().isBadRequest());
        // Ingest keys can't approve.
        mvc.perform(post("/api/actions/" + id + "/approve").header("X-API-Key", aliceIngestKey)).andExpect(status().isForbidden());
        // Owner approves: no webhook is configured, so execution fails safely and is recorded.
        mvc.perform(w(post("/api/actions/" + id + "/approve"), alice)).andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/actions").cookie(alice)).andReturn().getResponse().getContentAsString()).contains("FAILED");
        // A decided action can't be approved again.
        mvc.perform(w(post("/api/actions/" + id + "/approve"), alice)).andExpect(status().isBadRequest());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
