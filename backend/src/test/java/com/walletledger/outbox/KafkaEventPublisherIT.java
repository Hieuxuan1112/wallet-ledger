package com.walletledger.outbox;

import com.walletledger.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventPublisherIT extends AbstractKafkaIntegrationTest {

    @Autowired
    private KafkaEventPublisher publisher;

    @Test
    void publishSendsThePayloadToTheWalletEventsTopic() {
        OutboxEvent event = new OutboxEvent("LedgerTransaction", 999L,
                OutboxEvent.TRANSACTION_POSTED, "{\"amount\":\"12.5000\"}");

        publisher.publish(event);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-event-publisher-it");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // The Kafka container and its topic are shared by every Kafka test in the JVM, so other
        // tests' messages may sit on the same topic: match this test's own record by key instead
        // of assuming it is the only one, and poll until it shows up rather than once.
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps,
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaEventPublisher.TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (matching.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofSeconds(1)).forEach(r -> {
                    if ("999".equals(r.key())) {
                        matching.add(r);
                    }
                });
            }
        }

        assertThat(matching).hasSize(1);
        assertThat(matching.get(0).value()).isEqualTo("{\"amount\":\"12.5000\"}");
    }
}
