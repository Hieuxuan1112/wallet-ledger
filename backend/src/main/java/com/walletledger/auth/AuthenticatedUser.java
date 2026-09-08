package com.walletledger.auth;

/** What the JWT filter puts into the security context. Never built from request input. */
public record AuthenticatedUser(long id, String username, Role role) {
}
