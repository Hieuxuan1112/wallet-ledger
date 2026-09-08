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

class TransferDeadlockIT extends AbstractIntegrationTest {

    private static final BigDecimal ONE = new BigDecimal("1.0000");

    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private AppUser seeded(String prefix, String amount) {
        AppUser user = users.save(AppUser.create(prefix + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        money.deposit(user.getId(), new BigDecimal(amount), "seed");
        return user;
    }

    private BigDecimal balanceOf(AppUser user) {
        return accounts.findByOwnerUserId(user.getId()).orElseThrow().getBalance();
    }

    /**
     * The classic deadlock shape: A→B and B→A at the same time. Without a global lock ordering
     * each transaction holds one row and waits for the other, and PostgreSQL breaks the cycle by
     * aborting one with SQLSTATE 40P01 after deadlock_timeout. LedgerPostingService locks in
     * ascending account id order, so the cycle cannot form and no transfer is ever aborted.
     */
    @Test
    void simultaneousOppositeTransfersNeitherDeadlockNorLoseMoney() throws Exception {
        AppUser alice = seeded("alice-", "200.0000");
        AppUser bob = seeded("bob-", "200.0000");
        BigDecimal totalBefore = balanceOf(alice).add(balanceOf(bob));

        int eachWay = 50;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>(eachWay * 2);

        for (int i = 0; i < eachWay; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                money.transfer(alice.getId(), bob.getUsername(), ONE, "a to b");
                return null;
            }));
            results.add(pool.submit(() -> {
                startGate.await();
                money.transfer(bob.getId(), alice.getUsername(), ONE, "b to a");
                return null;
            }));
        }
        startGate.countDown();

        // Any deadlock arrives here as an ExecutionException wrapping CannotAcquireLockException.
        for (Future<Void> result : results) {
            result.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(balanceOf(alice).add(balanceOf(bob))).isEqualByComparingTo(totalBefore);
        assertThat(jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry", BigDecimal.class))
                .isEqualByComparingTo("0");
    }
}
