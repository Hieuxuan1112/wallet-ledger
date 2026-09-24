package com.walletledger.notification;

import java.time.Instant;

public record NotificationView(String type, String message, Instant createdAt) {

    static NotificationView of(Notification notification) {
        return new NotificationView(notification.getType(), notification.getMessage(),
                notification.getCreatedAt());
    }
}
