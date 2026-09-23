package com.walletledger.audit;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.money.InsufficientFundsException;
import com.walletledger.money.MoneyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Bug 9 proved the connection pool can be exhausted precisely because AuditLogger opens a second
 * connection while the caller still holds the first. When that happens the audit write fails
 * inside a catch block, and an exception thrown there replaces the one being handled.
 */
class AuditFailureIsolationIT extends AbstractIntegrationTest {

    // @MockitoBean gives this class its own cached ApplicationContext, and therefore its own
    // Hikari pool on top of the default context's — see bug #12/#14 in NHAT_KY_BUG.md. This test
    // is single-threaded, so it never needed the default's 64; a small pool leaves far more
    // headroom for whichever heavier-concurrency context happens to start next.
    @DynamicPropertySource
    static void smallerPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
    }

    @MockitoBean private AuditLogger audit;

    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;

    private AppUser newUserWithWallet() {
        AppUser user = users.save(AppUser.create("auditfail-" + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        return user;
    }

    @Test
    void aFailingAuditWriteDoesNotReplaceTheDomainException() {
        AppUser user = newUserWithWallet();
        doThrow(new CannotGetJdbcConnectionException("pool exhausted"))
                .when(audit).record(any(), any(), any(), any());

        assertThatThrownBy(() -> money.withdraw(user.getId(), new BigDecimal("1.0000"), "no funds"))
                .isInstanceOf(InsufficientFundsException.class);
    }
}
