package com.walletledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The request holding this key has not committed, so its response cannot be read and must not be
 * guessed. Stripe answers the same situation the same way: tell the client to retry shortly.
 */
public class IdempotencyInProgressException extends ErrorResponseException {

    public IdempotencyInProgressException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Request in progress");
        detail.setDetail("Another request with this Idempotency-Key is still running. Retry shortly.");
        return detail;
    }
}
