package com.walletledger.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The 72-character cap is not arbitrary: BCrypt silently ignores everything past 72 bytes,
 * so accepting a longer password would mean accepting one whose tail never matters.
 */
public record RegisterRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                 message = "username may contain letters, digits, dot, dash and underscore only")
        String username,

        @NotBlank
        @Size(min = 8, max = 72)
        String password) {
}
