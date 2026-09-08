package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginIT extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Test
    void loginReturnsAnAccessToken() throws Exception {
        register("judy");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(credentials("judy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void aValidTokenReachesTheWalletEndpoint() throws Exception {
        String token = accessTokenFor("karl");

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                // money is serialised as a JSON string: a NUMERIC(19,4) can exceed the 53-bit
                // integer precision of JavaScript's Number, and this API has a React client
                .andExpect(jsonPath("$.balance").value("0.0000"));
    }

    @Test
    void theWalletEndpointReturnsTheCallersOwnWallet() throws Exception {
        String oscarToken = accessTokenFor("oscar");
        register("peggy");

        AppUser oscar = users.findByUsername("oscar").orElseThrow();
        long oscarWalletId = accounts.findByOwnerUserId(oscar.getId()).orElseThrow().getId();

        // the endpoint takes no identifier at all: identity comes from the token, so there is
        // no parameter an attacker could change to read somebody else's wallet
        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(oscarToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(oscarWalletId));
    }

    @Test
    void aWrongPasswordAndAnUnknownUserAreIndistinguishable() throws Exception {
        register("niaj");

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"niaj","password":"wrong-password-here"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(credentials("nobody-at-all")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownUser);
    }
}
