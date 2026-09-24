package com.walletledger.ledger;

import com.walletledger.account.AccountRepository;

/**
 * Lets {@link AbstractConcurrencyContract}'s four subclasses share a single test-only Spring
 * context (and therefore a single Hikari pool) instead of one context each. A distinct
 * {@code @TestConfiguration} per subclass would work too, but each one is its own cache key —
 * four extra 20-ish-connection pools on top of the default context's 64 would flirt with
 * PostgreSQL's max_connections (100) on this shared container. Switching the delegate on one
 * bean costs nothing extra in connections.
 * <p>
 * {@code active} is set once per test method, before the concurrent threads that read it are
 * started, and never changed while they run — {@code volatile} only has to make that one
 * happens-before edge visible, not guard against concurrent writers.
 */
class SelectableBalanceMutator implements BalanceMutator {

    private final PessimisticBalanceMutator pessimistic;
    private final OptimisticBalanceMutator optimistic;
    private final SerializableBalanceMutator serializable;
    private final SynchronizedBalanceMutator synchronizedLock;
    private final UnsafeBalanceMutator unsafe;

    private volatile BalanceMutator active;

    SelectableBalanceMutator(AccountRepository accounts, PessimisticBalanceMutator pessimistic,
                             OptimisticBalanceMutator optimistic, SerializableBalanceMutator serializable,
                             SynchronizedBalanceMutator synchronizedLock) {
        this.pessimistic = pessimistic;
        this.optimistic = optimistic;
        this.serializable = serializable;
        this.synchronizedLock = synchronizedLock;
        this.unsafe = new UnsafeBalanceMutator(accounts);
        this.active = pessimistic;
    }

    void use(BalanceMutator strategy) {
        this.active = strategy;
    }

    BalanceMutator pessimistic() {
        return pessimistic;
    }

    BalanceMutator optimistic() {
        return optimistic;
    }

    BalanceMutator serializable() {
        return serializable;
    }

    BalanceMutator synchronizedLock() {
        return synchronizedLock;
    }

    BalanceMutator unsafe() {
        return unsafe;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return active.acquire(firstId, secondId);
    }

    @Override
    public String strategyName() {
        return active.strategyName();
    }
}
