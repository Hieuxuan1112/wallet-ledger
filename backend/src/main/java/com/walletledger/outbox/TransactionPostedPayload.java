package com.walletledger.outbox;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.UUID;

/** The exact JSON shape written to outbox_event.payload and read back by NotificationConsumer. */
public record TransactionPostedPayload(
        UUID transactionPublicId,
        Long userId,
        String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String description) {
}
