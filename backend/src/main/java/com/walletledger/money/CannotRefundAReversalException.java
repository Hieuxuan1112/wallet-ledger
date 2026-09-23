package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class CannotRefundAReversalException extends ErrorResponseException {

    public CannotRefundAReversalException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Cannot refund a reversal");
        detail.setDetail("A REVERSAL transaction cannot itself be refunded");
        return detail;
    }
}
