package com.walletledger.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Its own bean on purpose. Spring applies {@code @Transactional} through a proxy, so calling a
 * REQUIRES_NEW method on {@code this} goes straight to the method, silently joins the caller's
 * transaction, and is rolled back with it. Here that would mean the exception which rejects a
 * replayed token also undoes the revocation it triggered — the security response destroyed by
 * the security refusal.
 */
@Component
public class RefreshTokenRevoker {

    private final RefreshTokenRepository tokens;

    public RefreshTokenRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, Instant now) {
        return tokens.revokeFamily(familyId, now);
    }
}
