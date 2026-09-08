package com.walletledger.account;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * The balance is serialised as a JSON <em>string</em>, not a number. JSON numbers are read as
 * IEEE-754 doubles by many clients — JavaScript's Number among them — and a NUMERIC(19,4) can
 * carry more significant digits than a double holds exactly. Sending money as a number means
 * the React client silently rounds large balances.
 */
public record WalletView(
        long accountId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) {
}
