package com.walletledger.ledger;

import com.walletledger.account.Account;

/**
 * How the two accounts in a posting are acquired safely. The posting itself — adjusting the
 * balances and writing the entry pair — is identical for every strategy and stays in
 * LedgerPostingService. Only the acquisition differs, which is exactly what Phase 1C measures.
 */
public interface BalanceMutator {

    /** Called with ids in ascending order. Implementations must not reorder them. */
    AccountPair acquire(long firstId, long secondId);

    /** What the strategy is called in the measurement table. */
    String strategyName();

    record AccountPair(Account first, Account second) {
    }
}
