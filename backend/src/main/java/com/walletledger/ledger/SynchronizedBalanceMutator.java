package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The strategy a developer reaches for first, and the reason MultiInstanceIT exists: a Java lock
 * instead of a database lock. It is held from acquire() until the transaction completes, so within
 * one JVM nothing can read a balance another thread is about to change — SynchronizedConcurrencyIT
 * proves that. A second JVM has its own LOCK and sees none of this.
 * <p>
 * Never the production strategy. It is selected only by APP_LEDGER_MUTATOR=synchronized, which
 * scripts/multi-instance.sh sets for SynchronizedControlIT.
 */
// ponytail: one global lock for every account — fine for a control experiment, never for production.
@Component
@Primary
@ConditionalOnProperty(name = "app.ledger.mutator", havingValue = "synchronized")
public class SynchronizedBalanceMutator implements BalanceMutator {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private final AccountRepository accounts;

    public SynchronizedBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        LOCK.lock();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOCK.unlock();
            throw new IllegalStateException("SynchronizedBalanceMutator needs an active transaction");
        }
        // Released after commit or rollback, not when acquire() returns: releasing earlier would
        // let the next thread read the balance before this one's update is committed.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                LOCK.unlock();
            }
        });
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "synchronized";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
