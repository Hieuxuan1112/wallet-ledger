package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Deliberately carries neither the balance nor the shortfall. For a withdrawal or a transfer the
 * drawn account belongs to the caller, so a figure would be harmless — but the same exception is
 * raised for refunds in Phase 2, where the drawn account belongs to somebody else.
 */
public class InsufficientFundsException extends ErrorResponseException {

    public InsufficientFundsException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Insufficient funds");
        detail.setDetail("The account does not hold enough to complete this operation");
        return detail;
    }
}
