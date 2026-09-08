package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerPostingService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;

    public LedgerPostingService(AccountRepository accounts,
                                LedgerTransactionRepository transactions,
                                LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    /**
     * The only place in the application where a balance changes.
     * <p>
     * Runs at READ COMMITTED, PostgreSQL's default, and does not rely on it: correctness comes
     * from holding a row lock on both accounts for the whole read-modify-write, not from the
     * isolation level. The locks are taken in ascending account id order, which is what stops
     * A to B and B to A deadlocking — with one global ordering, two transactions can never each
     * hold what the other needs next.
     *
     * @param fromAccountId the account money leaves; receives the negative entry
     * @param toAccountId   the account money arrives in; receives the positive entry
     */
    @Transactional
    public LedgerTransaction post(TransactionType type, long initiatedByUserId, String description,
                                  long fromAccountId, long toAccountId, BigDecimal amount) {
        // The sign is checked here, not only in the request DTOs, because this method is the
        // chokepoint and every service-layer caller reaches it without passing through them.
        // A negative amount would reverse the direction of the posting: "pay 50 to B" becomes a
        // withdrawal from B, which ck_wallet_non_negative allows for as long as B stays solvent.
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("An account cannot pay itself");
        }

        // Two separate statements, lower id first. A single "where id in (a, b) order by id for
        // update" would NOT do: PostgreSQL makes no promise about the order in which it acquires
        // row locks within one statement, so only explicit sequential locking makes the ordering
        // a real guarantee.
        long firstId = Math.min(fromAccountId, toAccountId);
        long secondId = Math.max(fromAccountId, toAccountId);
        Account first = lock(firstId);
        Account second = lock(secondId);

        Account from = firstId == fromAccountId ? first : second;
        Account to = firstId == toAccountId ? first : second;

        try {
            from.debit(amount);
        } catch (IllegalStateException e) {
            throw new InsufficientFundsException();
        }
        to.credit(amount);

        LedgerTransaction transaction =
                transactions.save(LedgerTransaction.of(type, initiatedByUserId, description));
        entries.save(new LedgerEntry(transaction.getId(), from.getId(), amount.negate()));
        entries.save(new LedgerEntry(transaction.getId(), to.getId(), amount));

        return transaction;
    }

    private Account lock(long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
