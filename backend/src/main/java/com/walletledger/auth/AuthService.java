package com.walletledger.auth;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokens;

    /**
     * A real BCrypt hash of a fixed string, computed once at startup. It is compared against
     * when the username does not exist, so that path costs the same as a genuine check.
     */
    private final String dummyHash;

    public AuthService(AppUserRepository users, AccountRepository accounts,
                       PasswordEncoder passwordEncoder, TokenService tokenService,
                       RefreshTokenService refreshTokens) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-credential");
    }

    /**
     * The existsByUsername check is a courtesy, not the guarantee. Two simultaneous
     * registrations both see "available" and both insert; the unique index is what actually
     * decides, so its rejection is translated into the same 409. This is the first
     * check-then-act race in the codebase, and the whole of Phase 1B is the same shape of
     * problem applied to money.
     */
    @Transactional
    public AppUser register(String username, String rawPassword) {
        if (users.existsByUsername(username)) {
            throw new UsernameAlreadyTakenException(username);
        }
        try {
            AppUser user = users.saveAndFlush(
                    AppUser.create(username, passwordEncoder.encode(rawPassword)));
            accounts.saveAndFlush(Account.walletFor(user.getId()));
            return user;
        } catch (DataIntegrityViolationException e) {
            throw new UsernameAlreadyTakenException(username);
        }
    }

    /**
     * BCrypt runs on every call, including for usernames that do not exist. Returning early
     * for an unknown user makes that response measurably faster than a wrong-password
     * response, which is enough to enumerate valid accounts by timing alone.
     */
    @Transactional
    public LoginResponse login(String username, String rawPassword) {
        Optional<AppUser> found = users.findByUsername(username);
        String hash = found.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);

        if (found.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        AppUser user = found.get();
        // A new family per login, which is what makes logout device-scoped.
        String refreshToken = refreshTokens.issue(user.getId(), UUID.randomUUID());
        return new LoginResponse(tokenService.issueAccessToken(user), refreshToken);
    }

    @Transactional
    public LoginResponse refresh(String presentedRefreshToken) {
        RefreshToken consumed = refreshTokens.consume(presentedRefreshToken);
        AppUser user = users.findById(consumed.getUserId())
                .orElseThrow(InvalidCredentialsException::new);
        String rotated = refreshTokens.issue(user.getId(), consumed.getFamilyId());
        return new LoginResponse(tokenService.issueAccessToken(user), rotated);
    }

    @Transactional
    public void logout(String presentedRefreshToken) {
        refreshTokens.revokeFamilyOf(presentedRefreshToken);
    }
}
