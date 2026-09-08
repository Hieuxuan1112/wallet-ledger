package com.walletledger.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Extending ErrorResponseException makes Spring render RFC 7807 without a dedicated handler. */
public class UsernameAlreadyTakenException extends ErrorResponseException {

    public UsernameAlreadyTakenException(String username) {
        super(HttpStatus.CONFLICT, problem(username), null);
    }

    private static ProblemDetail problem(String username) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Username already taken");
        detail.setDetail("Username '%s' is already registered".formatted(username));
        return detail;
    }
}
