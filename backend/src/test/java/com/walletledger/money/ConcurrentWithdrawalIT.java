package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentWithdrawalIT extends AbstractIntegrationTest {

    private static final BigDecimal ONE = new BigDecimal("1.0000");

    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 150 threads each try to withdraw 1 from a wallet holding 100. Exactly 100 must succeed,
     * 50 must be refused, and the balance must land on exactly zero — never below it, and never
     * above it either, which is what a lost update would produce.
     */
    @Test
    void moreThreadsThanMoneyStillLeavesTheBalanceExact() throws Exception {
        AppUser user = users.save(AppUser.create("race-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        money.deposit(user.getId(), new BigDecimal("100.0000"), "seed");

        int attempts = 150;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>(attempts);

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    money.withdraw(user.getId(), ONE, "concurrent");
                    return Boolean.TRUE;
                } catch (InsufficientFundsException e) {
                    return Boolean.FALSE;
                }
            }));
        }
        startGate.countDown();

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            // Any other exception surfaces here as an ExecutionException and fails the test,
            // which is the point: only InsufficientFunds is an acceptable refusal.
            if (result.get(120, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded).isEqualTo(100);

        BigDecimal balance = jdbc.queryForObject(
                "select balance from account where id = ?", BigDecimal.class, wallet.getId());
        assertThat(balance).isEqualByComparingTo("0.0000");

        BigDecimal derived = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry where account_id = ?",
                BigDecimal.class, wallet.getId());
        assertThat(derived).isEqualByComparingTo("0.0000");

        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry where account_id = ? and amount < 0",
                Integer.class, wallet.getId())).isEqualTo(100);
    }
}
