package com.walletledger.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventPublisher implements EventPublisher {

    /** One topic for every event type this phase produces; event_type inside the JSON payload tells a consumer what it's looking at. */
    public static final String TOPIC = "wallet-events";

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(OutboxEvent event) {
        // Keyed by aggregate id: Kafka guarantees ordering within a partition, so every event
        // for the same transaction lands in the same partition in the order it was posted.
        kafka.send(TOPIC, event.getAggregateId().toString(), event.getPayload());
    }
}
