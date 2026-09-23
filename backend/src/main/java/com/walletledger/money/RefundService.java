package com.walletledger.money;

import com.walletledger.account.AccountRepository;
import com.walletledger.ledger.InsufficientBalanceException;
import com.walletledger.ledger.LedgerEntry;
import com.walletledger.ledger.LedgerEntryRepository;
import com.walletledger.ledger.LedgerTransaction;
import com.walletledger.ledger.LedgerTransactionRepository;
import com.walletledger.ledger.LedgerPostingService;
import com.walletledger.ledger.TransactionType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A refund is a new REVERSAL transaction whose entries are the exact negation of the original —
 * nothing is edited or deleted (V2__ledger.sql's append-only trigger would refuse an edit
 * anyway). "Exact negation" falls out of swapping the original's from/to accounts and reposting
 * the same amount through the same chokepoint every other money movement goes through.
 */
@Service
public class RefundService {

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final LedgerPostingService posting;
    private final AccountRepository accounts;

    public RefundService(LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                         LedgerPostingService posting, AccountRepository accounts) {
        this.transactions = transactions;
        this.entries = entries;
        this.posting = posting;
        this.accounts = accounts;
    }

    public TransactionView refund(long callerId, UUID originalPublicId, String description) {
        LedgerTransaction original = transactions.findByPublicId(originalPublicId)
                .orElseThrow(TransactionNotFoundException::new);

        if (original.getType() == TransactionType.REVERSAL) {
            throw new CannotRefundAReversalException();
        }
        if (original.getInitiatedByUserId() != callerId) {
            throw new NotTheInitiatorException();
        }

        List<LedgerEntry> originalEntries = entries.findByTransactionId(original.getId());
        LedgerEntry debited = originalEntries.stream()
                .filter(e -> e.getAmount().signum() < 0)
                .findFirst().orElseThrow();
        LedgerEntry credited = originalEntries.stream()
                .filter(e -> e.getAmount().signum() > 0)
                .findFirst().orElseThrow();
        BigDecimal amount = credited.getAmount();

        try {
            // Swapped: the refund takes money back FROM where it arrived and returns it TO
            // where it left, which is exactly what "exact negation" means for a two-entry pair.
            LedgerTransaction reversal = posting.post(TransactionType.REVERSAL, callerId, description,
                    credited.getAccountId(), debited.getAccountId(), amount, original.getId());
            // Neither credited nor debited is reliably "the caller's account": for a deposit the
            // caller's wallet is credited, for a withdrawal or transfer it's debited. Look the
            // caller's own wallet up directly instead of guessing from the entry signs.
            long callerWalletId = accounts.findWalletIdByOwnerUserId(callerId).orElseThrow();
            BigDecimal balanceAfter = accounts.findById(callerWalletId).orElseThrow().getBalance();
            return TransactionView.of(reversal, amount, balanceAfter);
        } catch (InsufficientBalanceException e) {
            throw new InsufficientFundsException();
        } catch (DataIntegrityViolationException e) {
            // The uq on ledger_transaction.reverses_transaction_id catches a race between two
            // concurrent refund attempts for the same original transaction; the pre-check above
            // only catches the non-concurrent case.
            throw new AlreadyRefundedException();
        }
    }
}
