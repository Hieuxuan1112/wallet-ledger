package com.walletledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@link UserDetailsServiceAutoConfiguration} is excluded because authentication here comes
 * only from a signed JWT. Left enabled it creates an in-memory user with a password printed at
 * startup; nothing is wired to it, but a phantom account in a money application is exactly the
 * kind of thing a security review should not have to reason about.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class WalletLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletLedgerApplication.class, args);
    }
}
