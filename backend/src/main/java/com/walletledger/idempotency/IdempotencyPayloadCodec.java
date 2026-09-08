package com.walletledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.money.TransactionView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class IdempotencyPayloadCodec {

    private final ObjectMapper objectMapper;

    public IdempotencyPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(TransactionView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a transaction view", e);
        }
    }

    public TransactionView decode(String json) {
        try {
            return objectMapper.readValue(json, TransactionView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored transaction view", e);
        }
    }

    /** Records serialise their components in declaration order, so this is stable. */
    public String canonicalise(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not canonicalise a request", e);
        }
    }

    public String hash(String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(requestBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
