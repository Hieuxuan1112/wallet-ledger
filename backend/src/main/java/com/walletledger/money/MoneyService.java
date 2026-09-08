package com.walletledger.money;

import com.walletledger.account.AccountRepository;
import com.walletledger.audit.AuditLogger;
import com.walletledger.audit.AuditOutcome;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.LedgerPostingService;
import com.walletledger.ledger.LedgerTransaction;
import com.walletledger.ledger.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

@Service
public class MoneyService {

    private static final Logger log = LoggerFactory.getLogger(MoneyService.class);

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
    private final AuditLogger audit;

    public MoneyService(AppUserRepository users, AccountRepository accounts,
                        LedgerPostingService posting, AuditLogger audit) {
        this.users = users;
        this.accounts = accounts;
        this.posting = posting;
        this.audit = audit;
    }

    @Transactional
    public TransactionView deposit(long userId, BigDecimal amount, String description) {
        try {
            long walletId = walletIdOf(userId);
            LedgerTransaction tx = posting.post(TransactionType.DEPOSIT, userId, description,
                    SYSTEM_FUNDING, walletId, amount);
            TransactionView view = TransactionView.of(tx, amount, balanceAfter(walletId));
            auditSuccess(userId, "DEPOSIT", "amount=" + amount);
            return view;
        } catch (RuntimeException e) {
            auditFailure(userId, "DEPOSIT", amount, e);
            throw e;
        }
    }

    @Transactional
    public TransactionView withdraw(long userId, BigDecimal amount, String description) {
        try {
            long walletId = walletIdOf(userId);
            LedgerTransaction tx = posting.post(TransactionType.WITHDRAWAL, userId, description,
                    walletId, SYSTEM_PAYOUT, amount);
            TransactionView view = TransactionView.of(tx, amount, balanceAfter(walletId));
            auditSuccess(userId, "WITHDRAWAL", "amount=" + amount);
            return view;
        } catch (RuntimeException e) {
            auditFailure(userId, "WITHDRAWAL", amount, e);
            throw e;
        }
    }

    @Transactional
    public TransactionView transfer(long fromUserId, String toUsername, BigDecimal amount, String description) {
        try {
            AppUser recipient = users.findByUsername(toUsername)
                    .orElseThrow(RecipientNotFoundException::new);
            if (recipient.getId() == fromUserId) {
                throw new SelfTransferException();
            }
            long fromWalletId = walletIdOf(fromUserId);
            long toWalletId = walletIdOf(recipient.getId());

            LedgerTransaction tx = posting.post(TransactionType.TRANSFER, fromUserId, description,
                    fromWalletId, toWalletId, amount);
            TransactionView view = TransactionView.of(tx, amount, balanceAfter(fromWalletId));
            // Both sides. Money arriving is as auditable as money leaving, and audit_log has one
            // user_id column, so this is two rows tied together by the transaction's public id.
            auditSuccess(fromUserId, "TRANSFER", "amount=" + amount + " out tx=" + tx.getPublicId());
            auditSuccess(recipient.getId(), "TRANSFER", "amount=" + amount + " in tx=" + tx.getPublicId());
            return view;
        } catch (RuntimeException e) {
            auditFailure(fromUserId, "TRANSFER", amount, e);
            throw e;
        }
    }

    /**
     * Deferred to afterCommit, so the row describes something that happened rather than something
     * that was attempted. AuditLogger's REQUIRES_NEW commits immediately, so recording success
     * inline would leave the log asserting a deposit that a failed COMMIT then threw away — the
     * deferred ledger-balance trigger only fires at commit time, which is exactly when this
     * matters. The failure path stays inline and keeps REQUIRES_NEW: there, surviving the
     * caller's rollback is the whole point.
     */
    private void auditSuccess(long userId, String action, String detail) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    audit.record(userId, action, detail, AuditOutcome.SUCCESS);
                } catch (RuntimeException e) {
                    // The money has already committed; losing the audit row must not fail the
                    // request on top of it. Loud in the log, silent to the caller.
                    log.error("Could not audit successful {} for user {}", action, userId, e);
                }
            }
        });
    }

    /**
     * The audit write must never replace the exception it is describing. Bug 9 showed the pool
     * can be exhausted by AuditLogger's own second connection, so this call really can throw —
     * and if it escaped, the caller would see a connection error instead of the 409 the domain
     * produced. Attached as suppressed so the diagnosis is not lost either.
     */
    private void auditFailure(long userId, String action, BigDecimal amount, RuntimeException cause) {
        try {
            audit.record(userId, action, "amount=" + amount + " rejected: "
                    + cause.getClass().getSimpleName(), AuditOutcome.FAILURE);
        } catch (RuntimeException auditFailure) {
            cause.addSuppressed(auditFailure);
        }
    }

    private long walletIdOf(long userId) {
        return accounts.findWalletIdByOwnerUserId(userId)
                .orElseThrow(WalletMissingException::new);
    }

    /** Read after posting: this returns the instance the posting service loaded under lock. */
    private BigDecimal balanceAfter(long walletId) {
        return accounts.findById(walletId).orElseThrow().getBalance();
    }
}
