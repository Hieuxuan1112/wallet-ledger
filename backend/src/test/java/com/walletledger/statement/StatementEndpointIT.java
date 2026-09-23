package com.walletledger.statement;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatementEndpointIT extends AbstractIntegrationTest {

    @Test
    void statementListsDepositsNewestFirst() throws Exception {
        String token = accessTokenFor("stmt-" + UUID.randomUUID());
        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(post("/api/v1/wallet/deposits")
                    .header("Authorization", bearer(token))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(APPLICATION_JSON)
                    .content("""
                            {"amount":"%d.0000","description":"d%d"}""".formatted(i, i)));
        }

        mockMvc.perform(get("/api/v1/statement").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].amount").value("2.0000"))
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));
    }

    @Test
    void statementRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/statement")).andExpect(status().isUnauthorized());
    }
}
