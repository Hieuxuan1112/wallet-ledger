package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshTokenIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}""".formatted(refreshToken);
    }

    private int usableTokensOf(String username) {
        return jdbc.queryForObject(
                "select count(*) from refresh_token t join app_user u on u.id = t.user_id "
                        + "where u.username = ? and t.revoked_at is null and t.used_at is null",
                Integer.class, username);
    }

    @Test
    void loginReturnsBothTokens() throws Exception {
        register("rupert");

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("rupert")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void refreshingRotatesTheToken() throws Exception {
        register("sybil");
        String first = loginTokens("sybil").get("refreshToken").asText();

        String second = objectMapper.readTree(
                        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                                        .content(refreshBody(first)))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void replayingAUsedTokenRevokesTheWholeFamilyAndTheRevocationSurvives() throws Exception {
        register("trent");
        String first = loginTokens("trent").get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody(first))).andExpect(status().isOk());

        // The same token a second time. This is either theft or a broken client, and the two
        // are indistinguishable, so the whole family goes.
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody(first))).andExpect(status().isUnauthorized());

        // The important assertion: the revocation must survive the exception that rejected the
        // request. If both run in one transaction, the rollback undoes the security response.
        assertThat(usableTokensOf("trent")).isZero();
    }

    @Test
    void logoutLeavesOtherSessionsAlone() throws Exception {
        register("uma");
        String sessionOne = loginTokens("uma").get("refreshToken").asText();
        String sessionTwo = loginTokens("uma").get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                .content(refreshBody(sessionOne))).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody(sessionTwo))).andExpect(status().isOk());
    }

    @Test
    void anUnknownRefreshTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody("this-token-was-never-issued"))).andExpect(status().isUnauthorized());
    }
}
