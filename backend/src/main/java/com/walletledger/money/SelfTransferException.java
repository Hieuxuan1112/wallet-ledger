package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class SelfTransferException extends ErrorResponseException {

    public SelfTransferException() {
        super(HttpStatus.BAD_REQUEST, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Self transfer");
        detail.setDetail("A wallet cannot transfer to itself");
        return detail;
    }
}
