package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void usernameIsUnique() {
        jdbc.update("insert into app_user (username, password_hash) values ('alice', 'hash')");

        assertThatThrownBy(() ->
                jdbc.update("insert into app_user (username, password_hash) values ('alice', 'other')"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void roleIsRestrictedToKnownValues() {
        // Spring's translated exception message carries only the SQL, not PostgreSQL's own
        // detail, so the constraint name has to be looked for across the whole cause chain.
        assertThatThrownBy(() ->
                jdbc.update("insert into app_user (username, password_hash, role) "
                        + "values ('mallory', 'hash', 'SUPERUSER')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_app_user_role");
    }

    @Test
    void refreshTokenHashIsUnique() {
        Long userId = jdbc.queryForObject(
                "insert into app_user (username, password_hash) values ('bob', 'hash') returning id",
                Long.class);
        jdbc.update("insert into refresh_token (user_id, token_hash, family_id, expires_at) "
                + "values (?, repeat('a', 64), gen_random_uuid(), now() + interval '7 days')", userId);

        assertThatThrownBy(() ->
                jdbc.update("insert into refresh_token (user_id, token_hash, family_id, expires_at) "
                        + "values (?, repeat('a', 64), gen_random_uuid(), now() + interval '7 days')", userId))
                .isInstanceOf(DuplicateKeyException.class);

        // Scoped to this test's own user: refresh_token is shared by every class in the JVM, and an
        // absolute count only holds when this class happens to run before the ones that add rows.
        assertThat(jdbc.queryForObject(
                "select count(*) from refresh_token where user_id = ?", Integer.class, userId)).isEqualTo(1);
    }
}
