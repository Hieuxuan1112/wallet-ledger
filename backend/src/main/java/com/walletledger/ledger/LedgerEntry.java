package com.walletledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Plain foreign-key columns rather than {@code @ManyToOne} associations. A ledger is written
 * once and read as a list, so there is no navigation to justify lazy proxies, and plain ids keep
 * entity creation trivially safe inside a locked section.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Signed. Negative leaves the account, positive arrives. The pair must sum to zero. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long transactionId, Long accountId, BigDecimal amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
