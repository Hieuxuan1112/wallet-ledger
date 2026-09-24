package com.walletledger.notification;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationEndpointIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notifications;

    @Test
    void aUserSeesOnlyTheirOwnNotificationsNewestFirst() throws Exception {
        String alice = "notif-a-" + UUID.randomUUID();
        String bob = "notif-b-" + UUID.randomUUID();
        // Real user ids from the register response: notification.user_id is a foreign key to
        // app_user, and an invented id would fail the insert (the Phase 2B plan's third bug).
        long aliceId = registeredId(alice);
        long bobId = registeredId(bob);
        notifications.save(new Notification(aliceId, "TRANSACTION_POSTED", "DEPOSIT of 1.0000", UUID.randomUUID()));
        notifications.save(new Notification(aliceId, "TRANSACTION_POSTED", "DEPOSIT of 2.0000", UUID.randomUUID()));
        notifications.save(new Notification(bobId, "TRANSACTION_POSTED", "DEPOSIT of 9.0000", UUID.randomUUID()));

        String token = loginTokens(alice).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].message").value("DEPOSIT of 2.0000"))
                .andExpect(jsonPath("$.content[1].message").value("DEPOSIT of 1.0000"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    private long registeredId(String username) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(credentials(username)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("userId").asLong();
    }
}
