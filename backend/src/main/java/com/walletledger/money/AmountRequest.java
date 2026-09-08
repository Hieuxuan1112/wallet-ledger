package com.walletledger.money;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull
        @DecimalMin(value = "0.0001", message = "amount must be positive")
        @Digits(integer = 15, fraction = 4, message = "amount supports at most 4 decimal places")
        BigDecimal amount,

        @Size(max = 255)
        String description) {
}
