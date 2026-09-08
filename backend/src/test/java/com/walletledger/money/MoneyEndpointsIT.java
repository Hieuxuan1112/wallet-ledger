package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MoneyEndpointsIT extends AbstractIntegrationTest {

    private String key() {
        return UUID.randomUUID().toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deposit(
            String token, String idemKey, String body) {
        return post("/api/v1/wallet/deposits")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", idemKey)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    @Test
    void aDepositReturnsTheNewBalance() throws Exception {
        String token = accessTokenFor("dep-" + UUID.randomUUID());

        mockMvc.perform(deposit(token, key(), """
                        {"amount":"120.5000","description":"salary"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value("120.5000"))
                .andExpect(jsonPath("$.balanceAfter").value("120.5000"));
    }

    @Test
    void sendingTheSameKeyTwiceChargesOnce() throws Exception {
        String token = accessTokenFor("dup-" + UUID.randomUUID());
        String idemKey = key();
        String body = """
                {"amount":"10.0000","description":"double click"}""";

        mockMvc.perform(deposit(token, idemKey, body)).andExpect(status().isCreated());
        mockMvc.perform(deposit(token, idemKey, body)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.balance").value("10.0000"));
    }

    @Test
    void aMissingIdempotencyKeyIsRejected() throws Exception {
        String token = accessTokenFor("nokey-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"1.0000"}"""))
                .andExpect(status().isBadRequest());
    }

    /** Five decimal places must be refused, never silently rounded. */
    @Test
    void anAmountFinerThanFourDecimalPlacesIsRejected() throws Exception {
        String token = accessTokenFor("scale-" + UUID.randomUUID());

        mockMvc.perform(deposit(token, key(), """
                        {"amount":"1.00001"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroOrNegativeAmountIsRejected() throws Exception {
        String token = accessTokenFor("sign-" + UUID.randomUUID());

        mockMvc.perform(deposit(token, key(), """
                        {"amount":"0"}""")).andExpect(status().isBadRequest());
        mockMvc.perform(deposit(token, key(), """
                        {"amount":"-5.0000"}""")).andExpect(status().isBadRequest());
    }

    @Test
    void withdrawingMoreThanTheBalanceIsAConflict() throws Exception {
        String token = accessTokenFor("over-" + UUID.randomUUID());
        mockMvc.perform(deposit(token, key(), """
                {"amount":"5.0000"}""")).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallet/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"5.0001"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }

    @Test
    void aTransferMovesMoneyAndIsVisibleToBothParties() throws Exception {
        String payerName = "payer-" + UUID.randomUUID();
        String payeeName = "payee-" + UUID.randomUUID();
        String payerToken = accessTokenFor(payerName);
        String payeeToken = accessTokenFor(payeeName);

        mockMvc.perform(deposit(payerToken, key(), """
                {"amount":"60.0000"}""")).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(payerToken))
                        .header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"toUsername":"%s","amount":"25.0000","description":"lunch"}""".formatted(payeeName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value("35.0000"));

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(payeeToken)))
                .andExpect(jsonPath("$.balance").value("25.0000"));
    }

    @Test
    void anUnauthenticatedMoneyRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"1.0000"}"""))
                .andExpect(status().isUnauthorized());
    }
}
