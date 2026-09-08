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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLoggerIT extends AbstractIntegrationTest {

    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private AppUser newUserWithWallet() {
        AppUser user = users.save(AppUser.create("audit-" + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        return user;
    }

    private int auditRows(long userId, String outcome) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where user_id = ? and outcome = ?",
                Integer.class, userId, outcome);
    }

    @Test
    void aSuccessfulOperationIsRecorded() {
        AppUser user = newUserWithWallet();

        money.deposit(user.getId(), new BigDecimal("10.0000"), "seed");

        assertThat(auditRows(user.getId(), "SUCCESS")).isEqualTo(1);
    }

    /**
     * The point of the whole task. The transaction rolls back, and the audit row must not roll
     * back with it — otherwise the only operations ever recorded are the ones that worked, and
     * the log is useless exactly where it matters.
     */
    @Test
    void aRefusedOperationStillLeavesATrail() {
        AppUser user = newUserWithWallet();

        assertThatThrownBy(() -> money.withdraw(user.getId(), new BigDecimal("1.0000"), "no funds"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(auditRows(user.getId(), "FAILURE")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry e join account a on a.id = e.account_id "
                        + "where a.owner_user_id = ?", Integer.class, user.getId())).isZero();
    }
}
