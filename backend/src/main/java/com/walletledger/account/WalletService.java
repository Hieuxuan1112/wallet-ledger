package com.walletledger.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final AccountRepository accounts;

    public WalletService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public WalletView forUser(long userId) {
        Account wallet = accounts.findByOwnerUserId(userId)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " has no wallet"));
        return new WalletView(wallet.getId(), wallet.getBalance());
    }
}
