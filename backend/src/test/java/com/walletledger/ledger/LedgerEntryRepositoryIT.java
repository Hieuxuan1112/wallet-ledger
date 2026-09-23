package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.LedgerEntryRepository.StatementRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerEntryRepositoryIT extends AbstractIntegrationTest {

    private static final long SYSTEM_FUNDING = 1L;

    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerPostingService posting;
    @Autowired private LedgerEntryRepository entries;

    @Test
    void findStatementReturnsNewestFirstAndPages() {
        AppUser user = users.save(AppUser.create("stmt-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        for (int i = 1; i <= 3; i++) {
            posting.post(TransactionType.DEPOSIT, user.getId(), "deposit " + i,
                    SYSTEM_FUNDING, wallet.getId(), new BigDecimal(i + ".0000"));
        }

        Page<StatementRow> page = entries.findStatement(wallet.getId(), null, null, null,
                PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        // Newest first: the third deposit (amount 3) comes before the second (amount 2).
        assertThat(page.getContent().get(0).getAmount()).isEqualByComparingTo("3.0000");
        assertThat(page.getContent().get(1).getAmount()).isEqualByComparingTo("2.0000");
    }

    @Test
    void findStatementFiltersByTypeAndDate() {
        AppUser user = users.save(AppUser.create("stmt2-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        posting.post(TransactionType.DEPOSIT, user.getId(), "deposit",
                SYSTEM_FUNDING, wallet.getId(), new BigDecimal("10.0000"));
        posting.post(TransactionType.WITHDRAWAL, user.getId(), "withdrawal",
                wallet.getId(), 2L, new BigDecimal("1.0000"));

        Page<StatementRow> deposits = entries.findStatement(wallet.getId(), null, null,
                TransactionType.DEPOSIT, PageRequest.of(0, 10));

        assertThat(deposits.getTotalElements()).isEqualTo(1);
        assertThat(deposits.getContent().get(0).getType()).isEqualTo(TransactionType.DEPOSIT);

        Page<StatementRow> future = entries.findStatement(wallet.getId(),
                Instant.now().plusSeconds(3600), null, null, PageRequest.of(0, 10));
        assertThat(future.getTotalElements()).isZero();
    }
}
