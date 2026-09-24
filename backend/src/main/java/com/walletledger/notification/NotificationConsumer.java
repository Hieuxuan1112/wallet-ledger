package com.walletledger.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.outbox.KafkaEventPublisher;
import com.walletledger.outbox.TransactionPostedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Delivery is at-least-once (spec section 7), so the same message can arrive twice. Idempotency
 * comes from notification.event_id UNIQUE, checked by catching DataIntegrityViolationException
 * on the second insert -- the same pattern RefundService.refund() already uses for its own
 * uq-backed idempotency (AlreadyRefundedException), not a new one invented here.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "kafka")
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final NotificationRepository notifications;
    private final ObjectMapper objectMapper;

    public NotificationConsumer(NotificationRepository notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaEventPublisher.TOPIC, groupId = "notification-consumer")
    public void onMessage(String json) {
        TransactionPostedPayload payload;
        try {
            payload = objectMapper.readValue(json, TransactionPostedPayload.class);
        } catch (JsonProcessingException e) {
            log.error("Could not parse an outbox payload, dropping it: {}", json, e);
            return;
        }

        Notification notification = new Notification(payload.userId(), "TRANSACTION_POSTED",
                message(payload), payload.transactionPublicId());
        try {
            notifications.save(notification);
        } catch (DataIntegrityViolationException e) {
            log.debug("Notification for event {} already recorded, ignoring the redelivery",
                    payload.transactionPublicId());
        }
    }

    private String message(TransactionPostedPayload payload) {
        return "%s of %s".formatted(payload.type(), payload.amount());
    }
}
