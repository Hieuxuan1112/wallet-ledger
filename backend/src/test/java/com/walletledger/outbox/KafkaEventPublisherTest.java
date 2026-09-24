package com.walletledger.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    /**
     * OutboxRelay marks an event published only if publish() returns normally. If publish()
     * returned before the broker answered, a rejected record would still be marked published and
     * never retried.
     */
    @Test
    void aRejectedSendFailsThePublish() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafka);
        OutboxEvent event = new OutboxEvent("LedgerTransaction", 7L, OutboxEvent.TRANSACTION_POSTED, "{}");

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("broker down");
    }
}
