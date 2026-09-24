package com.walletledger.outbox;

/**
 * Two implementations, selected by app.events.publisher (spec section 7): KafkaEventPublisher
 * where a real broker exists, LoggingEventPublisher where it doesn't. This interface is what
 * makes that choice a configuration change instead of a code change.
 */
public interface EventPublisher {

    void publish(OutboxEvent event);
}
