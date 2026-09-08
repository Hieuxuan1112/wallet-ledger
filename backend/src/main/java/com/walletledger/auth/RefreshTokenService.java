package com.walletledger.auth;

import com.walletledger.config.JwtProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository tokens;
    private final RefreshTokenRevoker revoker;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenRevoker revoker,
                               JwtProperties properties) {
        this.tokens = tokens;
        this.revoker = revoker;
        this.properties = properties;
    }

    public String issue(long userId, UUID familyId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.save(new RefreshToken(userId, hash(token), familyId,
                Instant.now().plus(properties.refreshTokenTtl())));
        return token;
    }

    @Transactional
    public RefreshToken consume(String presented) {
        Instant now = Instant.now();
        RefreshToken stored = tokens.findByTokenHash(hash(presented))
                .orElseThrow(InvalidCredentialsException::new);

        if (!stored.isUsable(now)) {
            // Either the token was stolen and is being replayed, or a legitimate client got
            // out of step. The two are indistinguishable from here, so the safe move is to
            // invalidate the whole family and make everyone on it log in again.
            revoker.revokeFamily(stored.getFamilyId(), now);
            throw new InvalidCredentialsException();
        }
        stored.markUsed(now);
        return stored;
    }

    @Transactional
    public void revokeFamilyOf(String presented) {
        tokens.findByTokenHash(hash(presented))
                .ifPresent(token -> revoker.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    /**
     * SHA-256, not BCrypt. The token is already 256 random bits, so there is no dictionary to
     * defend against, and BCrypt's per-value salt would make the hash unindexable: every
     * lookup would become a full scan plus a matches() call per row.
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
