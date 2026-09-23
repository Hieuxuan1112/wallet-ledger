package com.walletledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * What the API exposes. A sequence id would leak how many transactions the system has
     * processed and would let anyone guess neighbouring records.
     */
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Column(name = "reverses_transaction_id")
    private Long reversesTransactionId;

    @Column(name = "initiated_by_user_id", nullable = false)
    private Long initiatedByUserId;

    @Column(length = 255)
    private String description;

    protected LedgerTransaction() {
    }

    private LedgerTransaction(TransactionType type, Long initiatedByUserId, String description,
                              Long reversesTransactionId) {
        this.publicId = UUID.randomUUID();
        this.type = type;
        this.initiatedByUserId = initiatedByUserId;
        this.description = description;
        this.reversesTransactionId = reversesTransactionId;
    }

    public static LedgerTransaction of(TransactionType type, Long initiatedByUserId, String description) {
        return new LedgerTransaction(type, initiatedByUserId, description, null);
    }

    public static LedgerTransaction reversal(Long initiatedByUserId, String description,
                                             Long reversesTransactionId) {
        return new LedgerTransaction(TransactionType.REVERSAL, initiatedByUserId, description,
                reversesTransactionId);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public TransactionType getType() {
        return type;
    }

    public Long getInitiatedByUserId() {
        return initiatedByUserId;
    }

    public String getDescription() {
        return description;
    }

    public Long getReversesTransactionId() {
        return reversesTransactionId;
    }
}
