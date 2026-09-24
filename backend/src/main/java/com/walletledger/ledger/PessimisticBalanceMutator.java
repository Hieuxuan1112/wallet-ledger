package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The production strategy. Two separate SELECT ... FOR UPDATE statements, lower id first.
 * A single "where id in (a, b) order by id for update" would NOT do: PostgreSQL makes no promise
 * about the order in which it acquires row locks within one statement.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.ledger.mutator", havingValue = "pessimistic", matchIfMissing = true)
public class PessimisticBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public PessimisticBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(lock(firstId), lock(secondId));
    }

    @Override
    public String strategyName() {
        return "pessimistic";
    }

    private Account lock(long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
