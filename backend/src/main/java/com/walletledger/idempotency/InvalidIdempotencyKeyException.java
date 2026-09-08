package com.walletledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InvalidIdempotencyKeyException extends ErrorResponseException {

    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid Idempotency-Key");
        detail.setDetail("Idempotency-Key must be between 8 and 64 characters");
        return detail;
    }
}
