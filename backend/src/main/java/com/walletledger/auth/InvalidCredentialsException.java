package com.walletledger.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Carries no username and no reason, so the response for "no such user" is byte-for-byte the
 * same as for "wrong password". Telling the two apart lets an attacker enumerate accounts.
 */
public class InvalidCredentialsException extends ErrorResponseException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        detail.setTitle("Invalid credentials");
        detail.setDetail("Invalid username or password");
        return detail;
    }
}
