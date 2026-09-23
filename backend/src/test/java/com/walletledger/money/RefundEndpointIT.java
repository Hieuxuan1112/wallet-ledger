package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefundEndpointIT extends AbstractIntegrationTest {

    @Test
    void refundingADepositReturns201WithTheReversalType() throws Exception {
        String token = accessTokenFor("refund-" + UUID.randomUUID());

        String depositResponse = mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"25.0000","description":"seed"}"""))
                .andReturn().getResponse().getContentAsString();
        JsonNode deposit = objectMapper.readTree(depositResponse);
        String transactionId = deposit.get("transactionId").asText();

        mockMvc.perform(post("/api/v1/transactions/" + transactionId + "/refund")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.balanceAfter").value("0.0000"));
    }

    @Test
    void refundingTheSameTransactionTwiceIsConflict() throws Exception {
        String token = accessTokenFor("refund2-" + UUID.randomUUID());
        String depositResponse = mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"10.0000","description":"seed"}"""))
                .andReturn().getResponse().getContentAsString();
        String transactionId = objectMapper.readTree(depositResponse).get("transactionId").asText();

        mockMvc.perform(post("/api/v1/transactions/" + transactionId + "/refund")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transactions/" + transactionId + "/refund")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }
}
