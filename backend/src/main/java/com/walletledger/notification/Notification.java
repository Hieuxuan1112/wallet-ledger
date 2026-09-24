package com.walletledger.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    /** Unique: this is what makes redelivery a no-op (spec section 7). */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    protected Notification() {
    }

    public Notification(Long userId, String type, String message, UUID eventId) {
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.eventId = eventId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public UUID getEventId() {
        return eventId;
    }
}
