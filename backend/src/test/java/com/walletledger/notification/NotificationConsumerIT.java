package com.walletledger.notification;

import com.walletledger.AbstractKafkaIntegrationTest;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.outbox.KafkaEventPublisher;
import com.walletledger.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConsumerIT extends AbstractKafkaIntegrationTest {

    @Autowired
    private KafkaEventPublisher publisher;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private AppUserRepository users;

    @Test
    void aRedeliveredEventProducesExactlyOneNotification() {
        // A real user: notification.user_id is a foreign key, and a violation of it would be
        // swallowed by the consumer's duplicate-handling catch, so the test would just time out.
        long userId = users.save(AppUser.create("notif-" + UUID.randomUUID(), "hash")).getId();
        UUID transactionId = UUID.randomUUID();
        String payload = ("{\"transactionPublicId\":\"%s\",\"userId\":%d,"
                + "\"type\":\"DEPOSIT\",\"amount\":\"10.0000\",\"description\":\"seed\"}")
                .formatted(transactionId, userId);
        OutboxEvent event = new OutboxEvent("LedgerTransaction", 1L, OutboxEvent.TRANSACTION_POSTED, payload);

        // At-least-once delivery: the same event published twice must still leave exactly one row.
        publisher.publish(event);
        publisher.publish(event);

        awaitUntil(() -> notifications.findByEventId(transactionId).isPresent(), 10);

        Notification saved = notifications.findByEventId(transactionId).orElseThrow();
        assertThat(saved.getMessage()).contains("DEPOSIT").contains("10.0000");
        assertThat(saved.getUserId()).isEqualTo(userId);
    }
}
