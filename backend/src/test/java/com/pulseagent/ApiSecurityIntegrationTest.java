package com.pulseagent;

import com.pulseagent.security.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityIntegrationTest {

    static final String ADMIN = SecurityUnitTest.ADMIN;
    static final String INGEST = SecurityUnitTest.INGEST;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.security.admin-key-sha256", () -> ApiKeyService.sha256Hex(ADMIN));
        r.add("app.security.ingest-key-sha256", () -> ApiKeyService.sha256Hex(INGEST));
        r.add("app.security.session-secret", () -> "integration-test-session-secret-0123456789");
        r.add("app.llm.api-key", () -> "");
        r.add("app.knowledge.dir", () -> "../knowledge");
        r.add("app.knowledge.data-dir", () -> "target/test-data");
    }

    @Autowired MockMvc mvc;

    @Test
    void protectedEndpointsRequireAKey() throws Exception {
        mvc.perform(get("/api/investigations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/investigations").header("X-API-Key", "pa_admin_wrong_000000000000000000")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/investigations").header("X-API-Key", ADMIN)).andExpect(status().isOk());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void ingestKeyIsLeastPrivilege() throws Exception {
        mvc.perform(get("/api/investigations").header("X-API-Key", INGEST)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledge").header("X-API-Key", INGEST)).andExpect(status().isForbidden());
        String event = """
                {"service":"payment-service","exceptionClass":"java.lang.IllegalStateException","message":"boom",
                 "frames":[{"className":"com.demo.payment.PaymentService","methodName":"charge","fileName":"PaymentService.java","lineNumber":18}]}""";
        mvc.perform(post("/api/v1/errors").header("X-API-Key", INGEST).contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isAccepted());
    }

    @Test
    void inputIsValidated() throws Exception {
        mvc.perform(post("/api/v1/investigations").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"x\",\"service\":\"a b; rm -rf /\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("service")));
        String badFrame = """
                {"service":"svc","exceptionClass":"x.Y","frames":[{"className":"a.B; drop table","lineNumber":1}]}""";
        mvc.perform(post("/api/v1/errors").header("X-API-Key", INGEST).contentType(MediaType.APPLICATION_JSON).content(badFrame))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginSetsAHardenedCookieAndCookieWritesNeedCsrfHeader() throws Exception {
        var res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"" + ADMIN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("pa_session", true))
                .andExpect(cookie().secure("pa_session", true))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();
        var c = res.getResponse().getCookie("pa_session");
        String body = "{\"question\":\"Is anything wrong?\"}";
        mvc.perform(post("/api/investigations").cookie(c).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/investigations").cookie(c).header("X-Requested-With", "pulseagent")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"" + INGEST + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
