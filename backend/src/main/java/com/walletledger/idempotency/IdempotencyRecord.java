package com.walletledger.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;

    @Column(nullable = false, length = 64)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", length = 4096)
    private String responseBody;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, String idemKey, String endpoint, String requestHash) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
    }

    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    /** A row with no body is an operation still in flight. */
    public boolean isComplete() {
        return responseBody != null;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
