package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.stereotype.Component;

/**
 * No database lock. Account carries a @Version column, so Hibernate appends
 * "and version = ?" to the UPDATE and throws if another transaction got there first.
 * <p>
 * The retry has to happen ABOVE the transaction, not inside it: once a transaction has failed
 * its version check it is finished, and retrying inside it retries nothing. This bean only
 * acquires; the caller retries the whole {@code post} call. The production bean stays
 * pessimistic and never needs this, so the retry lives in the concurrency test contract
 * (AbstractConcurrencyContract) rather than as a production wrapper nothing would ever invoke.
 */
@Component
public class OptimisticBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public OptimisticBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "optimistic";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
