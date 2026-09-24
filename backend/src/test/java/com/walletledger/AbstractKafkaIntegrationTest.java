package com.walletledger;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A second static container, started once for the whole JVM exactly like POSTGRES in the
 * parent -- Ryuk is disabled here too, so the same shutdown-hook approach stops it. Kept as a
 * separate base class, not merged into AbstractIntegrationTest, so tests that never touch Kafka
 * never pay for starting it. Setting app.events.publisher=kafka here (not in the parent) is what
 * actually gives subclasses their own cached Spring context, separate from the shared
 * "logging"-publisher context every other integration test uses (bug #14's lesson: a changed
 * property set is a new context, a new Hikari pool, budgeted against POSTGRES's
 * max_connections=300).
 */
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        KAFKA.start();
        Runtime.getRuntime().addShutdownHook(new Thread(KAFKA::stop));
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("app.events.publisher", () -> "kafka");
    }

    /**
     * Kafka consumption is asynchronous -- a @KafkaListener runs on its own thread and a test
     * cannot know exactly when it has processed a message. Polling a condition is the standard
     * answer; this exists here rather than pulling in Awaitility as a new dependency for what a
     * ten-line loop already does.
     */
    protected void awaitUntil(BooleanSupplier condition, long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition not met within " + timeoutSeconds + "s");
    }
}
