package com.walletledger.statement;

import com.walletledger.account.AccountRepository;
import com.walletledger.ledger.LedgerEntryRepository;
import com.walletledger.ledger.TransactionType;
import com.walletledger.money.WalletMissingException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class StatementService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;

    public StatementService(AccountRepository accounts, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    public Page<StatementEntryView> forUser(long userId, Instant from, Instant to,
                                            TransactionType type, Pageable pageable) {
        long walletId = accounts.findWalletIdByOwnerUserId(userId)
                .orElseThrow(WalletMissingException::new);
        return entries.findStatement(walletId, from, to, type, pageable)
                .map(StatementEntryView::of);
    }
}
