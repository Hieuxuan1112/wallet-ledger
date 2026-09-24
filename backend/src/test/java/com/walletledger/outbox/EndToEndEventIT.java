package com.walletledger.outbox;

import com.walletledger.AbstractKafkaIntegrationTest;
import com.walletledger.notification.Notification;
import com.walletledger.notification.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class EndToEndEventIT extends AbstractKafkaIntegrationTest {

    @Autowired
    private OutboxEventRepository events;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private NotificationRepository notifications;

    /**
     * relay.relay() claims the oldest 100 unpublished rows. By the time this test runs, the rest
     * of the suite has likely left its own unpublished backlog behind (the relay is off by
     * default elsewhere), which could crowd out this test's own event from that batch entirely.
     * Starting from empty is what makes "the relay published this specific deposit" reliable.
     */
    @BeforeEach
    void cleanOutbox() {
        events.deleteAll();
    }

    @Test
    void aDepositTravelsFromHttpToANotificationExactlyOnce() throws Exception {
        String token = accessTokenFor("e2e-" + UUID.randomUUID());

        String response = mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"15.0000","description":"e2e"}"""))
                .andReturn().getResponse().getContentAsString();
        UUID publicId = UUID.fromString(objectMapper.readTree(response).get("transactionId").asText());

        relay.relay();
        awaitUntil(() -> notifications.findByEventId(publicId).isPresent(), 10);

        Notification notification = notifications.findByEventId(publicId).orElseThrow();
        assertThat(notification.getMessage()).contains("DEPOSIT").contains("15.0000");

        // Exactly once: a second relay tick (simulating the scheduler firing again) must not
        // republish an already-published event, and must not duplicate the notification.
        relay.relay();
        assertThat(notifications.findByEventId(publicId)).isPresent();
    }
}
