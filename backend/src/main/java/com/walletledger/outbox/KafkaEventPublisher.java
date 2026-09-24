package com.walletledger.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventPublisher implements EventPublisher {

    /** One topic for every event type this phase produces; event_type inside the JSON payload tells a consumer what it's looking at. */
    public static final String TOPIC = "wallet-events";

    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(OutboxEvent event) {
        // Keyed by aggregate id: Kafka guarantees ordering within a partition, so every event
        // for the same transaction lands in the same partition in the order it was posted.
        // send() is asynchronous. Waiting for the acknowledgement is what makes the relay's
        // markPublished() true: without it a rejected record is marked published and lost.
        try {
            kafka.send(TOPIC, event.getAggregateId().toString(), event.getPayload())
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox event " + event.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge outbox event " + event.getId(), e);
        }
    }
}
