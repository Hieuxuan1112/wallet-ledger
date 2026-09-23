package com.walletledger.ledger;

/** The production strategy. No overrides: PessimisticBalanceMutator is already @Primary. */
class PessimisticConcurrencyIT extends AbstractConcurrencyContract {

    @Override
    protected BalanceMutator strategyUnderTest() {
        return selectable.pessimistic();
    }
}
