package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 150-thread withdrawal and the opposite-transfer test, run identically against all four
 * {@link BalanceMutator} strategies. If each strategy had its own copy of these tests, a
 * difference in the measured numbers could come from a difference in the tests instead of a
 * difference in the strategy — running one shared body is what makes the comparison mean
 * anything. Subclasses pick a strategy and, where the strategy needs it, a retry policy; see
 * {@link PessimisticConcurrencyIT}, {@link OptimisticConcurrencyIT},
 * {@link SerializableConcurrencyIT} and {@link UnsafeConcurrencyIT}.
 */
// A nested @TestConfiguration is only auto-detected in the concrete test class Spring loads, not
// in a superclass — so without this explicit @Import, each subclass would boot with no
// SelectableBalanceMutator bean at all. @Import, unlike plain nested-class detection, is honoured
// across the class hierarchy.
@Import(AbstractConcurrencyContract.SelectableStrategy.class)
abstract class AbstractConcurrencyContract extends AbstractIntegrationTest {

    private static final long SYSTEM_FUNDING = 1L;
    private static final long SYSTEM_PAYOUT = 2L;
    private static final BigDecimal ONE = new BigDecimal("1.0000");

    @TestConfiguration
    static class SelectableStrategy {
        // Overrides the bean named "pessimisticBalanceMutator" rather than adding a second
        // @Primary BalanceMutator candidate: two beans of the same type both marked @Primary is an
        // ambiguous wiring error, not something Spring resolves in the test's favour (see bug #12
        // in NHAT_KY_BUG.md). This one bean serves all four subclasses so they share one context
        // and one connection pool — see SelectableBalanceMutator's own javadoc.
        // Built from fresh instances rather than autowiring the existing PessimisticBalanceMutator/
        // OptimisticBalanceMutator/SerializableBalanceMutator beans: overriding "pessimisticBalanceMutator"
        // by name replaces that bean definition entirely, so a parameter asking
        // for PessimisticBalanceMutator here would have nothing left to inject. All four strategies
        // are stateless wrappers over AccountRepository, so a second instance behaves identically.
        @Bean("pessimisticBalanceMutator")
        @Primary
        SelectableBalanceMutator selectableBalanceMutator(AccountRepository accounts) {
            return new SelectableBalanceMutator(accounts,
                    new PessimisticBalanceMutator(accounts),
                    new OptimisticBalanceMutator(accounts),
                    new SerializableBalanceMutator(accounts));
        }

        @Bean
        SerializablePostingGateway serializablePostingGateway(LedgerPostingService posting) {
            return new SerializablePostingGateway(posting);
        }
    }

    // This shared context still needs its own pool, on top of the default context's 64 — see the
    // max_connections story in bug #12 (NHAT_KY_BUG.md). None of these paths use AuditLogger's
    // REQUIRES_NEW, so one connection per thread is enough.
    @DynamicPropertySource
    static void smallerPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "24");
    }

    @Autowired protected LedgerPostingService posting;
    @Autowired protected SelectableBalanceMutator selectable;
    @Autowired protected SerializablePostingGateway serializableGateway;
    @Autowired protected AppUserRepository users;
    @Autowired protected AccountRepository accounts;
    @Autowired protected JdbcTemplate jdbc;

    protected final AtomicInteger retries = new AtomicInteger();

    protected abstract BalanceMutator strategyUnderTest();

    /** Pessimistic and serializable always succeed or fail on the merits; only unsafe does not. */
    protected boolean rejectsWithoutRetrying(RuntimeException e) {
        return e instanceof InsufficientBalanceException;
    }

    /**
     * True for every strategy except unsafe. Pessimistic never conflicts, and optimistic and
     * serializable retry until they succeed or genuinely run out of money — so all three land on
     * exactly the numbers a single-threaded run would produce. Unsafe does not retry (see
     * bug #13), so a legitimate attempt can still lose to a version conflict; the
     * sum stays exact, but the individual outcome counts do not.
     */
    protected boolean mustBeCorrect() {
        return true;
    }

    /** Overridden by optimistic and serializable to retry above the transaction boundary. */
    protected LedgerTransaction post(TransactionType type, long userId, String description,
                                     long fromId, long toId, BigDecimal amount) {
        return posting.post(type, userId, description, fromId, toId, amount);
    }

    @BeforeEach
    void chooseStrategy() {
        selectable.use(strategyUnderTest());
        retries.set(0);
    }

    @Test
    void moreThreadsThanMoneyStillLeavesTheBalanceExact() throws Exception {
        AppUser user = users.save(AppUser.create("contract-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        posting.post(TransactionType.DEPOSIT, user.getId(), "seed",
                SYSTEM_FUNDING, wallet.getId(), new BigDecimal("100.0000"));

        int attempts = 150;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>(attempts);

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    post(TransactionType.WITHDRAWAL, user.getId(), "concurrent",
                            wallet.getId(), SYSTEM_PAYOUT, ONE);
                    return Boolean.TRUE;
                } catch (RuntimeException e) {
                    if (rejectsWithoutRetrying(e)) {
                        return Boolean.FALSE;
                    }
                    throw e;
                }
            }));
        }
        startGate.countDown();

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(120, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        BigDecimal cached = jdbc.queryForObject(
                "select balance from account where id = ?", BigDecimal.class, wallet.getId());
        BigDecimal derived = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry where account_id = ?",
                BigDecimal.class, wallet.getId());

        System.out.printf("%s withdrawals: successes=%d retries=%d cached=%s derived=%s%n",
                strategyUnderTest().strategyName(), succeeded, retries.get(), cached, derived);

        // True for every strategy here: @Version (see bug #13 in NHAT_KY_BUG.md) stops the cached
        // balance from ever drifting from what the ledger actually recorded, independent of the
        // acquisition strategy. Only a strategy that retries is guaranteed to reach exactly 100
        // successes — one with no retry can lose legitimate attempts to a version conflict.
        assertThat(cached).isEqualByComparingTo(derived);
        assertThat(succeeded).isLessThanOrEqualTo(100);
        assertThat(cached).isEqualByComparingTo(
                new BigDecimal("100.0000").subtract(new BigDecimal(succeeded).multiply(ONE)));
        if (mustBeCorrect()) {
            assertThat(succeeded).isEqualTo(100);
        }
    }

    @Test
    void simultaneousOppositeTransfersNeitherDeadlockNorLoseMoney() throws Exception {
        AppUser alice = users.save(AppUser.create("alice-" + UUID.randomUUID(), "hash"));
        AppUser bob = users.save(AppUser.create("bob-" + UUID.randomUUID(), "hash"));
        Account walletAlice = accounts.save(Account.walletFor(alice.getId()));
        Account walletBob = accounts.save(Account.walletFor(bob.getId()));
        posting.post(TransactionType.DEPOSIT, alice.getId(), "seed",
                SYSTEM_FUNDING, walletAlice.getId(), new BigDecimal("200.0000"));
        posting.post(TransactionType.DEPOSIT, bob.getId(), "seed",
                SYSTEM_FUNDING, walletBob.getId(), new BigDecimal("200.0000"));
        BigDecimal totalBefore = new BigDecimal("400.0000");

        int eachWay = 50;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>(eachWay * 2);

        for (int i = 0; i < eachWay; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                transfer(alice.getId(), "a to b", walletAlice.getId(), walletBob.getId());
                return null;
            }));
            results.add(pool.submit(() -> {
                startGate.await();
                transfer(bob.getId(), "b to a", walletBob.getId(), walletAlice.getId());
                return null;
            }));
        }
        startGate.countDown();
        for (Future<Void> result : results) {
            result.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        BigDecimal balanceAlice = accounts.findById(walletAlice.getId()).orElseThrow().getBalance();
        BigDecimal balanceBob = accounts.findById(walletBob.getId()).orElseThrow().getBalance();

        System.out.printf("%s transfers: retries=%d balanceAlice=%s balanceBob=%s%n",
                strategyUnderTest().strategyName(), retries.get(), balanceAlice, balanceBob);

        assertThat(balanceAlice.add(balanceBob)).isEqualByComparingTo(totalBefore);
        if (mustBeCorrect()) {
            assertThat(balanceAlice).isEqualByComparingTo("200.0000");
            assertThat(balanceBob).isEqualByComparingTo("200.0000");
        }
    }

    /** A rejected transfer (unsafe only) simply doesn't happen — the sum invariant still holds. */
    private void transfer(long fromUserId, String description, long fromWalletId, long toWalletId) {
        try {
            post(TransactionType.TRANSFER, fromUserId, description, fromWalletId, toWalletId, ONE);
        } catch (RuntimeException e) {
            if (!rejectsWithoutRetrying(e)) {
                throw e;
            }
        }
    }
}
