package com.walletledger.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingEventPublisherTest {

    @Test
    void publishingNeverThrows() {
        OutboxEvent event = new OutboxEvent("LedgerTransaction", 1L,
                OutboxEvent.TRANSACTION_POSTED, "{\"amount\":\"1.0000\"}");
        LoggingEventPublisher publisher = new LoggingEventPublisher();

        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}
