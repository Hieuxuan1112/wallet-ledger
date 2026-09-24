package com.walletledger.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public Page<NotificationView> forUser(long userId, Pageable pageable) {
        return notifications.findByUserIdOrderByIdDesc(userId, pageable).map(NotificationView::of);
    }
}
