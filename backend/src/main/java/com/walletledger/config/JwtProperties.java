package com.walletledger.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The signing key deliberately has no default anywhere: not here, and not in
 * application.yml. A default baked into the jar is a publicly known key that anybody who
 * forgets an environment variable will happily sign real tokens with. Failing at startup,
 * naming the variable, is the correct behaviour.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(

        @NotBlank
        @Size(min = 32, message = "APP_JWT_SECRET must be at least 32 characters")
        String secret,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl) {
}
