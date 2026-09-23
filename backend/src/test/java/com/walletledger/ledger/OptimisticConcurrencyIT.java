package com.walletledger.ledger;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

/**
 * No database lock — {@code @Version} does the work, and Hibernate appends "and version = ?" to
 * the UPDATE. The retry lives here, above {@code LedgerPostingService.post}'s own transaction
 * boundary: once a transaction has failed its version check it is finished, and retrying inside
 * it retries nothing. Each loop iteration is a fresh call through the Spring proxy, so it starts
 * a genuinely new transaction.
 */
class OptimisticConcurrencyIT extends AbstractConcurrencyContract {

    @Override
    protected BalanceMutator strategyUnderTest() {
        return selectable.optimistic();
    }

    @Override
    protected LedgerTransaction post(TransactionType type, long userId, String description,
                                     long fromId, long toId, BigDecimal amount) {
        for (int attempt = 1; ; attempt++) {
            try {
                return posting.post(type, userId, description, fromId, toId, amount);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= 200) {
                    throw e;
                }
                retries.incrementAndGet();
            }
        }
    }
}
