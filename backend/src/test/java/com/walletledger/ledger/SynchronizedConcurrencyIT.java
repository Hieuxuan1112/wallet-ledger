package com.walletledger.ledger;

/**
 * Inside one JVM a Java lock held until commit is as correct as FOR UPDATE: the shared contract
 * must pass unchanged. SynchronizedControlIT (multi-instance) then shows the same strategy failing
 * once three JVMs share the database, which is the whole point of having it.
 */
class SynchronizedConcurrencyIT extends AbstractConcurrencyContract {

    @Override
    protected BalanceMutator strategyUnderTest() {
        return selectable.synchronizedLock();
    }
}
