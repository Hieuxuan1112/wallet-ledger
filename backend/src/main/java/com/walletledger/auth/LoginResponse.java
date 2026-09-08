package com.walletledger.auth;

/** {@code refreshToken} stays null until Task 7 introduces rotation. */
public record LoginResponse(String accessToken, String refreshToken) {
}
