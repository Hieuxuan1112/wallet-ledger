package com.walletledger.outbox;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository events;

    /**
     * claimBatch's assertions below depend on exactly which rows are unpublished at test time.
     * Once Task 2 lands, every money-moving test in the whole suite leaves its own unpublished
     * row here (the relay is off by default), so this table accumulates suite-wide backlog
     * across the run. Starting from empty is what keeps these three tests deterministic
     * regardless of what ran before them or in what order.
     */
    @BeforeEach
    void cleanOutbox() {
        events.deleteAll();
    }

    @Test
    void savedEventRoundTripsItsJsonbPayload() throws Exception {
        OutboxEvent saved = events.save(new OutboxEvent("LedgerTransaction", 1L,
                OutboxEvent.TRANSACTION_POSTED, "{\"amount\":\"10.0000\"}"));

        OutboxEvent loaded = events.findById(saved.getId()).orElseThrow();
        // jsonb normalises whitespace ({"amount": "10.0000"}), so compare as JSON, not as text.
        assertThat(objectMapper.readTree(loaded.getPayload()))
                .isEqualTo(objectMapper.readTree("{\"amount\":\"10.0000\"}"));
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getAttempts()).isZero();
    }

    @Test
    void claimBatchOnlyReturnsUnpublishedEventsOldestFirst() {
        OutboxEvent first = events.save(new OutboxEvent("LedgerTransaction", 1L,
                OutboxEvent.TRANSACTION_POSTED, "{}"));
        OutboxEvent second = events.save(new OutboxEvent("LedgerTransaction", 2L,
                OutboxEvent.TRANSACTION_POSTED, "{}"));
        OutboxEvent alreadyPublished = events.save(new OutboxEvent("LedgerTransaction", 3L,
                OutboxEvent.TRANSACTION_POSTED, "{}"));
        alreadyPublished.markPublished();
        events.save(alreadyPublished);

        List<OutboxEvent> batch = events.claimBatch(10);

        assertThat(batch).extracting(OutboxEvent::getId).containsExactly(first.getId(), second.getId());
    }

    @Test
    void claimBatchRespectsTheLimit() {
        for (int i = 0; i < 5; i++) {
            events.save(new OutboxEvent("LedgerTransaction", (long) i, OutboxEvent.TRANSACTION_POSTED, "{}"));
        }

        assertThat(events.claimBatch(2)).hasSize(2);
    }
}
