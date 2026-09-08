package com.walletledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountType type;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * Not used by the default money path, which locks the row with SELECT ... FOR UPDATE.
     * It exists so Phase 1B can implement an optimistic alternative and measure the two
     * against the same concurrency tests.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Account() {
    }

    private Account(AccountType type, Long ownerUserId) {
        this.type = type;
        this.ownerUserId = ownerUserId;
    }

    public static Account walletFor(Long ownerUserId) {
        return new Account(AccountType.USER_WALLET, ownerUserId);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /**
     * Fails fast and readably. The real guarantee is the ck_wallet_non_negative constraint
     * in the database, which holds even for SQL that never passes through this class.
     */
    public void debit(BigDecimal amount) {
        BigDecimal next = this.balance.subtract(amount);
        if (type == AccountType.USER_WALLET && next.signum() < 0) {
            throw new IllegalStateException("Wallet balance would go negative");
        }
        this.balance = next;
    }

    public Long getId() {
        return id;
    }

    public AccountType getType() {
        return type;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }
}
