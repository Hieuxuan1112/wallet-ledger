package com.walletledger.statement;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.walletledger.ledger.LedgerEntryRepository.StatementRow;
import com.walletledger.ledger.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementEntryView(
        UUID transactionId,
        TransactionType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String description,
        Instant createdAt) {

    public static StatementEntryView of(StatementRow row) {
        return new StatementEntryView(row.getPublicId(), row.getType(), row.getAmount(),
                row.getDescription(), row.getCreatedAt());
    }
}
