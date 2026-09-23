package com.walletledger.statement;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.LedgerPostingService;
import com.walletledger.ledger.TransactionType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Not an assertion-bearing test in the usual sense -- it exists to print EXPLAIN ANALYZE output
 * for a seeded large account into the build log, which Task 6's Step 2 then reads and records.
 * Disabled by default because seeding 20,000 rows takes real time on every build; run it by name
 * when the number needs re-measuring.
 */
@Disabled("Seeds 20,000 rows; run explicitly with -Dit.test=StatementQueryPerformanceIT when re-measuring")
class StatementQueryPerformanceIT extends AbstractIntegrationTest {

    private static final long SYSTEM_FUNDING = 1L;

    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerPostingService posting;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void explainAnalyzeOnTwentyThousandEntries() {
        AppUser user = users.save(AppUser.create("perf-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        for (int i = 0; i < 20_000; i++) {
            posting.post(TransactionType.DEPOSIT, user.getId(), "seed " + i,
                    SYSTEM_FUNDING, wallet.getId(), new BigDecimal("0.0001"));
        }

        List<String> planLast = jdbc.queryForList("""
                explain analyze
                select e.id from ledger_entry e
                 where e.account_id = ?
                 order by e.created_at desc, e.id desc
                 limit 20 offset 19980
                """, String.class, wallet.getId());
        List<String> planFirst = jdbc.queryForList("""
                explain analyze
                select e.id from ledger_entry e
                 where e.account_id = ?
                 order by e.created_at desc, e.id desc
                 limit 20 offset 0
                """, String.class, wallet.getId());

        System.out.println("=== EXPLAIN ANALYZE, offset 0 (first page) ===");
        planFirst.forEach(System.out::println);
        System.out.println("=== EXPLAIN ANALYZE, offset 19980 (last page of 20000) ===");
        planLast.forEach(System.out::println);
    }
}
