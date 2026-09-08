package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationIT extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    /** This class varies the password on purpose, so it builds its own bodies. */
    private static String body(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    @Test
    void registeringCreatesExactlyOneWalletAtZero() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("frank", "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("frank"));

        AppUser user = users.findByUsername("frank").orElseThrow();
        Account wallet = accounts.findByOwnerUserId(user.getId()).orElseThrow();

        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(accounts.findAll().stream()
                .filter(a -> user.getId().equals(a.getOwnerUserId()))
                .count()).isEqualTo(1);
    }

    @Test
    void thePasswordIsNeverStoredAsGiven() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("grace", "password123")))
                .andExpect(status().isCreated());

        String stored = users.findByUsername("grace").orElseThrow().getPasswordHash();

        assertThat(stored).doesNotContain("password123").startsWith("$2");
    }

    @Test
    void aDuplicateUsernameIsRejectedWithConflict() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("heidi", "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("heidi", "different123")))
                .andExpect(status().isConflict());
    }

    @Test
    void aShortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("ivan", "short")))
                .andExpect(status().isBadRequest());
    }
}
