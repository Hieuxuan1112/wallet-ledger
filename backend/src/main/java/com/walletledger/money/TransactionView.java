package com.walletledger.money;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.walletledger.ledger.LedgerTransaction;
import com.walletledger.ledger.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

/** Amounts are JSON strings for the same reason WalletView's balance is: JavaScript numbers. */
public record TransactionView(
        UUID transactionId,
        TransactionType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfter) {

    public static TransactionView of(LedgerTransaction transaction, BigDecimal amount, BigDecimal balanceAfter) {
        return new TransactionView(transaction.getPublicId(), transaction.getType(), amount, balanceAfter);
    }
}
