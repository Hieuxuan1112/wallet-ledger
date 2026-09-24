package com.walletledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The fallback used where no free Kafka host exists (spec sections 7 and 11's deployment table).
 * Logging the event is enough to keep the relay's contract -- every claimed event still gets
 * marked published -- without a real broker.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "logging")
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("event published (logging fallback): type={} aggregateId={} payload={}",
                event.getEventType(), event.getAggregateId(), event.getPayload());
    }
}
