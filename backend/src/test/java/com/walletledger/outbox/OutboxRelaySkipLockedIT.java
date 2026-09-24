package com.walletledger.outbox;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves FOR UPDATE SKIP LOCKED, not just that claimBatch works once run-to-completion. Two
 * threads each open a transaction and claim a batch; the first is held open past its claim (via
 * a latch) so the second thread's claim genuinely overlaps it in time. MultiInstanceIT (phase 3)
 * re-proves the same property across real JVMs behind a load balancer -- this is the same
 * mechanism, exercised inside one.
 */
class OutboxRelaySkipLockedIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Without this, claimBatch(5) in each thread could return stale unpublished rows left behind
     * by earlier tests (the relay is off by default) instead of this test's own freshly-seeded
     * 10, breaking the "exactly 5 and 5, no overlap" assertion depending on what ran before.
     */
    @BeforeEach
    void cleanOutbox() {
        events.deleteAll();
    }

    @Test
    void twoConcurrentClaimsNeverShareAnEvent() throws Exception {
        for (int i = 0; i < 10; i++) {
            events.save(new OutboxEvent("LedgerTransaction", (long) i, OutboxEvent.TRANSACTION_POSTED, "{}"));
        }
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch firstClaimTaken = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<List<Long>> firstBatch = pool.submit(() -> txTemplate.execute(status -> {
            List<OutboxEvent> claimed = events.claimBatch(5);
            firstClaimTaken.countDown();
            awaitLatch(releaseFirst);
            claimed.forEach(OutboxEvent::markPublished);
            events.saveAll(claimed);
            return claimed.stream().map(OutboxEvent::getId).collect(Collectors.toList());
        }));
        firstClaimTaken.await(5, TimeUnit.SECONDS);

        Future<List<Long>> secondBatch = pool.submit(() -> txTemplate.execute(status -> {
            List<OutboxEvent> claimed = events.claimBatch(5);
            claimed.forEach(OutboxEvent::markPublished);
            events.saveAll(claimed);
            return claimed.stream().map(OutboxEvent::getId).collect(Collectors.toList());
        }));
        releaseFirst.countDown();

        List<Long> first = firstBatch.get(5, TimeUnit.SECONDS);
        List<Long> second = secondBatch.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(first).hasSize(5);
        assertThat(second).hasSize(5);
        assertThat(Set.copyOf(first)).doesNotContainAnyElementsOf(second);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
