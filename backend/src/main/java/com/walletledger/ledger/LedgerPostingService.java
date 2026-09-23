package com.walletledger.ledger;

import com.walletledger.account.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerPostingService {

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final BalanceMutator mutator;

    public LedgerPostingService(LedgerTransactionRepository transactions,
                                LedgerEntryRepository entries,
                                BalanceMutator mutator) {
        this.transactions = transactions;
        this.entries = entries;
        this.mutator = mutator;
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
        return post(type, initiatedByUserId, description, fromAccountId, toAccountId, amount, null);
    }

    /**
     * The reversal link is required for TransactionType.REVERSAL and forbidden for everything
     * else — the same rule the ck_reversal_link CHECK enforces in V2__ledger.sql. Checking it
     * here, not only in the database, gives a caller an IllegalArgumentException instead of a
     * DataIntegrityViolationException surfacing from three layers down.
     */
    @Transactional
    public LedgerTransaction post(TransactionType type, long initiatedByUserId, String description,
                                  long fromAccountId, long toAccountId, BigDecimal amount,
                                  Long reversesTransactionId) {
        if ((type == TransactionType.REVERSAL) != (reversesTransactionId != null)) {
            throw new IllegalArgumentException(
                    "reversesTransactionId is required for, and only for, TransactionType.REVERSAL");
        }
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
        BalanceMutator.AccountPair pair = mutator.acquire(firstId, secondId);
        Account first = pair.first();
        Account second = pair.second();

        Account from = firstId == fromAccountId ? first : second;
        Account to = firstId == toAccountId ? first : second;

        try {
            from.debit(amount);
        } catch (IllegalStateException e) {
            throw new InsufficientBalanceException();
        }
        to.credit(amount);

        LedgerTransaction transaction = transactions.save(reversesTransactionId == null
                ? LedgerTransaction.of(type, initiatedByUserId, description)
                : LedgerTransaction.reversal(initiatedByUserId, description, reversesTransactionId));
        entries.save(new LedgerEntry(transaction.getId(), from.getId(), amount.negate()));
        entries.save(new LedgerEntry(transaction.getId(), to.getId(), amount));

        return transaction;
    }
}
