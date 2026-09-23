package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.stereotype.Component;

/**
 * The acquisition is a plain read; the isolation level does the work, declared on the
 * transactional method that wraps this call (see the SERIALIZABLE test contract subclass).
 * PostgreSQL implements SERIALIZABLE as Serializable Snapshot Isolation: it does not lock, it
 * detects a dangerous read-write pattern at commit and aborts one side with SQLSTATE 40001
 * (serialization_failure). PostgreSQL's own documentation says applications using this level
 * must be prepared to retry — retry is mandatory here, not optional, and lives in the caller for
 * the same reason it does for the optimistic strategy: a transaction that already failed cannot
 * retry itself from inside.
 */
@Component
public class SerializableBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public SerializableBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "serializable";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
