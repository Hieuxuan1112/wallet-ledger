package com.walletledger.outbox;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OutboxRelayIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxEventRepository events;

    @Autowired
    private OutboxRelay relay;

    @MockitoBean
    private EventPublisher publisher;

    /**
     * outbox_event is shared across the whole JVM's test run and the relay is off by default
     * elsewhere, so unrelated tests' unpublished rows accumulate. relay.relay() would otherwise
     * claim and publish that backlog too -- starting empty makes "publishes exactly these" real.
     */
    @BeforeEach
    void cleanOutbox() {
        events.deleteAll();
    }

    @Test
    void relayPublishesEveryUnpublishedEventAndMarksItPublished() {
        OutboxEvent first = events.save(new OutboxEvent("LedgerTransaction", 101L,
                OutboxEvent.TRANSACTION_POSTED, "{\"a\":1}"));
        OutboxEvent second = events.save(new OutboxEvent("LedgerTransaction", 102L,
                OutboxEvent.TRANSACTION_POSTED, "{\"a\":2}"));

        relay.relay();

        verify(publisher, times(1)).publish(argThat(e -> e.getId().equals(first.getId())));
        verify(publisher, times(1)).publish(argThat(e -> e.getId().equals(second.getId())));
        assertThat(events.findById(first.getId()).orElseThrow().getPublishedAt()).isNotNull();
        assertThat(events.findById(second.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void relaySkipsAlreadyPublishedEvents() {
        OutboxEvent published = events.save(new OutboxEvent("LedgerTransaction", 103L,
                OutboxEvent.TRANSACTION_POSTED, "{}"));
        published.markPublished();
        events.save(published);

        relay.relay();

        verify(publisher, never()).publish(any());
    }

    @Test
    void aFailedPublishLeavesTheEventUnpublishedForTheNextTick() {
        OutboxEvent event = events.save(new OutboxEvent("LedgerTransaction", 104L,
                OutboxEvent.TRANSACTION_POSTED, "{}"));
        doThrow(new RuntimeException("broker unreachable")).when(publisher).publish(any());

        relay.relay();

        OutboxEvent reloaded = events.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }
}
