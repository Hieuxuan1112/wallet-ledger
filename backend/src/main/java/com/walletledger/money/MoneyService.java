package com.walletledger.money;

import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.LedgerPostingService;
import com.walletledger.ledger.LedgerTransaction;
import com.walletledger.ledger.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class MoneyService {

    /**
     * Seeded with these exact ids by V2__ledger.sql, and LedgerSchemaIT keeps the constants
     * honest. Looking them up by type on every request would scan a table that grows with every
     * user, to learn something the migration already guarantees.
     */
    private static final long SYSTEM_FUNDING = 1L;
    private static final long SYSTEM_PAYOUT = 2L;

    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final LedgerPostingService posting;

    public MoneyService(AppUserRepository users, AccountRepository accounts, LedgerPostingService posting) {
        this.users = users;
        this.accounts = accounts;
        this.posting = posting;
    }

    @Transactional
    public TransactionView deposit(long userId, BigDecimal amount, String description) {
        long walletId = walletIdOf(userId);
        LedgerTransaction tx = posting.post(TransactionType.DEPOSIT, userId, description,
                SYSTEM_FUNDING, walletId, amount);
        return TransactionView.of(tx, amount, balanceAfter(walletId));
    }

    @Transactional
    public TransactionView withdraw(long userId, BigDecimal amount, String description) {
        long walletId = walletIdOf(userId);
        LedgerTransaction tx = posting.post(TransactionType.WITHDRAWAL, userId, description,
                walletId, SYSTEM_PAYOUT, amount);
        return TransactionView.of(tx, amount, balanceAfter(walletId));
    }

    @Transactional
    public TransactionView transfer(long fromUserId, String toUsername, BigDecimal amount, String description) {
        AppUser recipient = users.findByUsername(toUsername)
                .orElseThrow(RecipientNotFoundException::new);
        if (recipient.getId() == fromUserId) {
            throw new SelfTransferException();
        }
        long fromWalletId = walletIdOf(fromUserId);
        long toWalletId = walletIdOf(recipient.getId());

        LedgerTransaction tx = posting.post(TransactionType.TRANSFER, fromUserId, description,
                fromWalletId, toWalletId, amount);
        return TransactionView.of(tx, amount, balanceAfter(fromWalletId));
    }

    private long walletIdOf(long userId) {
        return accounts.findWalletIdByOwnerUserId(userId)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " has no wallet"));
    }

    /** Read after posting: this returns the instance the posting service loaded under lock. */
    private BigDecimal balanceAfter(long walletId) {
        return accounts.findById(walletId).orElseThrow().getBalance();
    }
}
