package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.auth.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
 * No lock, no retry. Bug #13 (docs/hoc/NHAT_KY_BUG.md): the {@code @Version} column still catches
 * every genuine conflict — Hibernate checks it on any managed entity's flush, regardless of how
 * the entity was read — so this strategy never corrupts the balance. What it loses, with nothing
 * retrying a version conflict, is throughput: a legitimate attempt can be rejected for a reason
 * that has nothing to do with insufficient funds. The shared contract's two tests already cover
 * that with {@code mustBeCorrect() == false}; {@link #withoutRetryVersionConflictsShowUpAsLostThroughput()}
 * below is the original control experiment that found it, kept for the explicit version-conflict
 * count and printed numbers.
 */
class UnsafeConcurrencyIT extends AbstractConcurrencyContract {

    private static final long SYSTEM_FUNDING = 1L;
    private static final long SYSTEM_PAYOUT = 2L;
    private static final BigDecimal ONE = new BigDecimal("1.0000");

    @Override
    protected BalanceMutator strategyUnderTest() {
        return selectable.unsafe();
    }

    @Override
    protected boolean rejectsWithoutRetrying(RuntimeException e) {
        return e instanceof InsufficientBalanceException || e instanceof ObjectOptimisticLockingFailureException;
    }

    @Override
    protected boolean mustBeCorrect() {
        return false;
    }

    /**
     * The control experiment for the whole phase — and it does NOT reproduce a silent lost
     * update. {@link UnsafeBalanceMutator} still reads through {@code accounts.findById()}, a
     * JPA-managed read, so two overlapping withdrawals don't silently overwrite each other; the
     * second one to flush gets an {@link ObjectOptimisticLockingFailureException} instead. This
     * is a real finding, not a failure to force — see bug #13.
     */
    @Test
    void withoutRetryVersionConflictsShowUpAsLostThroughput() throws Exception {
        AppUser user = users.save(AppUser.create("unsafe-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        posting.post(TransactionType.DEPOSIT, user.getId(), "seed",
                SYSTEM_FUNDING, wallet.getId(), new BigDecimal("100.0000"));

        int attempts = 150;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>(attempts);

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    posting.post(TransactionType.WITHDRAWAL, user.getId(), "concurrent",
                            wallet.getId(), SYSTEM_PAYOUT, ONE);
                    return "success";
                } catch (InsufficientBalanceException e) {
                    return "insufficient-funds";
                } catch (ObjectOptimisticLockingFailureException e) {
                    return "version-conflict";
                }
            }));
        }
        startGate.countDown();

        int succeeded = 0;
        AtomicInteger versionConflicts = new AtomicInteger();
        for (Future<String> result : results) {
            // Any other exception surfaces here as an ExecutionException and fails the test: only
            // these two outcomes are acceptable rejections.
            switch (result.get(120, TimeUnit.SECONDS)) {
                case "success" -> succeeded++;
                case "version-conflict" -> versionConflicts.incrementAndGet();
                default -> { }
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        BigDecimal cached = accounts.findById(wallet.getId()).orElseThrow().getBalance();

        System.out.printf("UNSAFE: successes=%d versionConflicts=%d cached=%s%n",
                succeeded, versionConflicts.get(), cached);

        // The version column caught real contention: without it, 150 threads racing 32
        // connections against a balance of 100 would not all serialise cleanly.
        assertThat(versionConflicts.get()).isGreaterThan(0);
        // No overspend either: @Version enforces this structurally, independent of the strategy.
        assertThat(succeeded).isLessThanOrEqualTo(100);
        assertThat(cached).isEqualByComparingTo(
                new BigDecimal("100.0000").subtract(new BigDecimal(succeeded).multiply(ONE)));
    }
}
