package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;

/**
 * Deliberately broken, and deliberately unreachable from production code — it lives under
 * src/test/java so no wiring accident can select it in a running application.
 * <p>
 * It reads both accounts with no lock of any kind. Two transactions can therefore read the same
 * balance, both decide they can afford the withdrawal, and both write: a lost update.
 */
public class UnsafeBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public UnsafeBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "unsafe";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
