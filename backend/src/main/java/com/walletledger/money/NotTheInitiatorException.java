package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 403, not 404: the transaction exists and the caller knows it (they have its public id). */
public class NotTheInitiatorException extends ErrorResponseException {

    public NotTheInitiatorException() {
        super(HttpStatus.FORBIDDEN, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        detail.setTitle("Not the initiator");
        detail.setDetail("Only the user who initiated a transaction may refund it");
        return detail;
    }
}
