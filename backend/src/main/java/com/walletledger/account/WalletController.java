package com.walletledger.account;

import com.walletledger.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * Takes no identifier. The account is derived from the token, so there is no parameter an
     * attacker could change to read somebody else's wallet. The same rule governs the AI layer
     * in Phase 4: the user id is attached by the server, never taken from input.
     */
    @GetMapping
    public WalletView get(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.forUser(user.id());
    }
}
