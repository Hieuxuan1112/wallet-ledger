package com.walletledger.audit;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.InsufficientFundsException;
import com.walletledger.money.MoneyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
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
