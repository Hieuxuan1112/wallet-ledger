package com.walletledger.security;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProblemDetailIT extends AbstractIntegrationTest {

    @Test
    void anUnauthenticatedRequestGives401NotForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/wallet"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anUnreadableTokenIsAlsoUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/wallet")
                        .header(HttpHeaders.AUTHORIZATION, bearer("not.a.real.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = accessTokenFor("mallory");
        String tampered = token.substring(0, token.length() - 2) + "xy";

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The endpoint does not exist yet and must not be created here — it belongs to Phase 1B.
     * Spring Security authorises before routing, so a non-admin token is refused with 403
     * before anything looks for a handler. If this ever starts returning 404, authorisation
     * stopped running, which is a far more serious finding than a missing endpoint.
     */
    @Test
    void anAuthenticatedUserWithoutTheAdminRoleGives403() throws Exception {
        String token = accessTokenFor("victor");

        mockMvc.perform(get("/api/v1/admin/reconcile").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void aValidationFailureIsAlsoAProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"x","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
