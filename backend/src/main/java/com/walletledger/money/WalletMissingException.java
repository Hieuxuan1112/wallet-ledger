package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An authenticated user with no wallet is a broken invariant, not a bad request — registration
 * always creates one — so this stays a 500. It is an ErrorResponseException rather than a raw
 * IllegalStateException so that the answer is still RFC 7807, which is the whole reason
 * spring.mvc.problemdetails.enabled is on. The detail carries no user id: an error body is the
 * wrong place to publish internal identifiers.
 */
public class WalletMissingException extends ErrorResponseException {

    public WalletMissingException() {
        super(HttpStatus.INTERNAL_SERVER_ERROR, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Wallet unavailable");
        detail.setDetail("This account has no wallet");
        return detail;
    }
}
