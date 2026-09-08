package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntityIT extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private LedgerTransactionRepository transactions;

    @Autowired
    private LedgerEntryRepository entries;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Account newWallet() {
        AppUser user = users.save(AppUser.create("ledger-" + UUID.randomUUID(), "hash"));
        return accounts.save(Account.walletFor(user.getId()));
    }

    @Test
    void aBalancedPairPersists() {
        Account wallet = newWallet();

        LedgerTransaction saved = transactionTemplate.execute(status -> {
            LedgerTransaction tx = transactions.save(
                    LedgerTransaction.of(TransactionType.DEPOSIT, wallet.getOwnerUserId(), "test deposit"));
            entries.save(new LedgerEntry(tx.getId(), 1L, new BigDecimal("-25.0000")));
            entries.save(new LedgerEntry(tx.getId(), wallet.getId(), new BigDecimal("25.0000")));
            return tx;
        });

        assertThat(saved.getPublicId()).isNotNull();
        assertThat(saved.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(entries.findByTransactionId(saved.getId())).hasSize(2);
        assertThat(transactions.findByPublicId(saved.getPublicId())).isPresent();
    }

    @Test
    void anUnbalancedPairIsStillRejectedAtCommitThroughJpa() {
        Account wallet = newWallet();

        // The deferred constraint trigger does not care that the write came from Hibernate.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            LedgerTransaction tx = transactions.save(
                    LedgerTransaction.of(TransactionType.DEPOSIT, wallet.getOwnerUserId(), "broken"));
            entries.save(new LedgerEntry(tx.getId(), wallet.getId(), new BigDecimal("25.0000")));
        })).hasStackTraceContaining("Unbalanced transaction");
    }
}
