package com.walletledger.idempotency;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.InsufficientFundsException;
import com.walletledger.money.MoneyService;
import com.walletledger.money.TransactionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceIT extends AbstractIntegrationTest {

    private static final String ENDPOINT = "POST /api/v1/wallet/deposits";

    @Autowired private IdempotencyService idempotency;
    @Autowired private IdempotentExecutor executor;
    @Autowired private IdempotencyPayloadCodec codec;
    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private AppUser newUserWithWallet() {
        AppUser user = users.save(AppUser.create("idem-" + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        return user;
    }

    private BigDecimal balanceOf(AppUser user) {
        return accounts.findByOwnerUserId(user.getId()).orElseThrow().getBalance();
    }

    @Test
    void theSameKeyTwiceProducesOneTransactionAndReplaysTheResponse() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":\"25.0000\"}";

        TransactionView first = idempotency.execute(user.getId(), key, ENDPOINT, body,
                () -> money.deposit(user.getId(), new BigDecimal("25.0000"), "once"));
        TransactionView replay = idempotency.execute(user.getId(), key, ENDPOINT, body,
                () -> money.deposit(user.getId(), new BigDecimal("25.0000"), "once"));

        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(balanceOf(user)).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry e join account a on a.id = e.account_id "
                        + "where a.owner_user_id = ?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRejected() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();

        idempotency.execute(user.getId(), key, ENDPOINT, "{\"amount\":\"10.0000\"}",
                () -> money.deposit(user.getId(), new BigDecimal("10.0000"), "first"));

        assertThatThrownBy(() -> idempotency.execute(user.getId(), key, ENDPOINT, "{\"amount\":\"99.0000\"}",
                () -> money.deposit(user.getId(), new BigDecimal("99.0000"), "different")))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(balanceOf(user)).isEqualByComparingTo("10.0000");
    }

    @Test
    void twoUsersMayUseTheSameKeyIndependently() {
        AppUser a = newUserWithWallet();
        AppUser b = newUserWithWallet();
        String key = "shared-" + UUID.randomUUID();

        idempotency.execute(a.getId(), key, ENDPOINT, "{\"amount\":\"5.0000\"}",
                () -> money.deposit(a.getId(), new BigDecimal("5.0000"), "a"));
        idempotency.execute(b.getId(), key, ENDPOINT, "{\"amount\":\"7.0000\"}",
                () -> money.deposit(b.getId(), new BigDecimal("7.0000"), "b"));

        assertThat(balanceOf(a)).isEqualByComparingTo("5.0000");
        assertThat(balanceOf(b)).isEqualByComparingTo("7.0000");
    }

    /**
     * The catch in IdempotencyService exists for one thing: somebody else claimed this key first.
     * Every other integrity failure — a rejected ledger balance, a violated CHECK — must reach the
     * caller as itself, not disguised as "retry shortly", which is advice that makes those worse.
     */
    @Test
    void anIntegrityFailureFromTheOperationIsNotDisguisedAsInProgress() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.execute(user.getId(), key, ENDPOINT, "{}",
                () -> {
                    throw new DataIntegrityViolationException("ledger imbalance");
                }))
                .isNotInstanceOf(IdempotencyInProgressException.class);
    }

    /**
     * Documents what the database actually raises, because guessing it wrong breaks idempotency
     * silently. Through JPA, Hibernate maps a unique violation to the generic
     * DataIntegrityViolationException — NOT to Spring's DuplicateKeyException, which only the
     * JDBC translator produces. So the collision has to be recognised by constraint name.
     */
    @Test
    void claimingTheSameKeyTwiceRaisesAViolationNamingTheUniqueIndex() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();
        String hash = codec.hash("{\"amount\":\"1.0000\"}");

        executor.claimAndRun(user.getId(), key, ENDPOINT, hash,
                () -> money.deposit(user.getId(), new BigDecimal("1.0000"), "first"));

        assertThatThrownBy(() -> executor.claimAndRun(user.getId(), key, ENDPOINT, hash,
                () -> money.deposit(user.getId(), new BigDecimal("1.0000"), "second")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uq_idempotency_user_key");
    }

    /**
     * The other half of narrowing the catch: a genuine race on one key must still be answered,
     * not turned into a raw 500. Every loser either replays the winner's response or is told the
     * request is in progress; nothing else is acceptable, and the money moves exactly once.
     */
    @Test
    void aRaceOnOneKeyChargesOnceAndRefusesTheLosersCleanly() throws Exception {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":\"3.0000\"}";

        int attempts = 16;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>(attempts);

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    return idempotency.execute(user.getId(), key, ENDPOINT, body,
                            () -> money.deposit(user.getId(), new BigDecimal("3.0000"), "race"))
                            .transactionId().toString();
                } catch (IdempotencyInProgressException e) {
                    return "IN_PROGRESS";
                }
            }));
        }
        startGate.countDown();

        // Anything other than a view or IdempotencyInProgressException surfaces here and fails.
        for (Future<String> result : results) {
            result.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(balanceOf(user)).isEqualByComparingTo("3.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry e join account a on a.id = e.account_id "
                        + "where a.owner_user_id = ?", Integer.class, user.getId())).isEqualTo(1);
    }

    /**
     * A failed operation must not consume its key. Recording failures too would mean a client
     * retrying after a transient error could never succeed, and retrying is what clients do.
     */
    @Test
    void aFailedOperationLeavesNoClaimedKeyBehind() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.execute(user.getId(), key, ENDPOINT, "{\"amount\":\"5.0000\"}",
                () -> money.withdraw(user.getId(), new BigDecimal("5.0000"), "no funds")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from idempotency_key where user_id = ? and idem_key = ?",
                Integer.class, user.getId(), key)).isZero();
    }
}
