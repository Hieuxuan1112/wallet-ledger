package com.walletledger.auth;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository users, AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
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
}
