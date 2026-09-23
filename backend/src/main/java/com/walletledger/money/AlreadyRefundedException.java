package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Matches spec 9's ALREADY_REFUNDED, backed by the uq on ledger_transaction.reverses_transaction_id. */
public class AlreadyRefundedException extends ErrorResponseException {

    public AlreadyRefundedException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Already refunded");
        detail.setDetail("This transaction has already been refunded");
        return detail;
    }
}
