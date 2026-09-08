package com.walletledger.idempotency;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencySchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long newUser() {
        return jdbc.queryForObject(
                "insert into app_user (username, password_hash) values (md5(random()::text), 'hash') returning id",
                Long.class);
    }

    @Test
    void theSameKeyCannotBeClaimedTwiceByOneUser() {
        long userId = newUser();
        jdbc.update("insert into idempotency_key (user_id, idem_key, endpoint, request_hash) "
                + "values (?, 'key-1', 'POST /transfers', 'hash')", userId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into idempotency_key (user_id, idem_key, endpoint, request_hash) "
                        + "values (?, 'key-1', 'POST /transfers', 'hash')", userId))
                .isInstanceOf(DuplicateKeyException.class)
                .hasStackTraceContaining("uq_idempotency_user_key");
    }

    @Test
    void twoUsersMayUseTheSameKey() {
        jdbc.update("insert into idempotency_key (user_id, idem_key, endpoint, request_hash) "
                + "values (?, 'shared-key', 'POST /transfers', 'hash')", newUser());
        jdbc.update("insert into idempotency_key (user_id, idem_key, endpoint, request_hash) "
                + "values (?, 'shared-key', 'POST /transfers', 'hash')", newUser());

        assertThat(jdbc.queryForObject(
                "select count(*) from idempotency_key where idem_key = 'shared-key'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void auditOutcomeIsRestrictedToKnownValues() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into audit_log (action, outcome) values ('TRANSFER', 'MAYBE')"))
                .hasStackTraceContaining("ck_audit_outcome");
    }

    @Test
    void anAuditRowSurvivesWithoutAUser() {
        jdbc.update("insert into audit_log (action, outcome) values ('LOGIN', 'FAILURE')");

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action = 'LOGIN'", Integer.class))
                .isEqualTo(1);
    }
}
