package com.walletledger.notification;

import com.walletledger.auth.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Like WalletController: no user id parameter exists, so there is nothing to tamper with. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Page<NotificationView> get(@AuthenticationPrincipal AuthenticatedUser user, Pageable pageable) {
        return notifications.forUser(user.id(), pageable);
    }
}
