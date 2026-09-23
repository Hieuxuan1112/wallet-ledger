package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class TransactionNotFoundException extends ErrorResponseException {

    public TransactionNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Transaction not found");
        detail.setDetail("No transaction exists with that id");
        return detail;
    }
}
