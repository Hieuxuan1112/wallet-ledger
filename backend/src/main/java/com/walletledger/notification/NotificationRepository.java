package com.walletledger.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByEventId(UUID eventId);

    Page<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
}
