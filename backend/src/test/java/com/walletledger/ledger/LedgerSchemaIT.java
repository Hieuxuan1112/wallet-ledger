package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long newUser() {
        return jdbc.queryForObject(
                "insert into app_user (username, password_hash) values (md5(random()::text), 'hash') returning id",
                Long.class);
    }

    private long newWallet() {
        return jdbc.queryForObject(
                "insert into account (type, owner_user_id) values ('USER_WALLET', ?) returning id",
                Long.class, newUser());
    }

    private long newTransaction() {
        return jdbc.queryForObject(
                "insert into ledger_transaction (public_id, type, initiated_by_user_id) "
                        + "values (gen_random_uuid(), 'DEPOSIT', ?) returning id",
                Long.class, newUser());
    }

    @Test
    void systemAccountsAreSeededWithFixedIds() {
        assertThat(jdbc.queryForObject("select type from account where id = 1", String.class))
                .isEqualTo("SYSTEM_FUNDING");
        assertThat(jdbc.queryForObject("select type from account where id = 2", String.class))
                .isEqualTo("SYSTEM_PAYOUT");
    }

    @Test
    void userWalletCannotGoNegative() {
        long wallet = newWallet();

        assertThatThrownBy(() -> jdbc.update("update account set balance = -1 where id = ?", wallet))
                .hasStackTraceContaining("ck_wallet_non_negative");
    }

    @Test
    void systemAccountsMayGoNegative() {
        jdbc.update("update account set balance = -500 where id = 1");

        assertThat(jdbc.queryForObject("select balance from account where id = 1", BigDecimal.class))
                .isEqualByComparingTo("-500");

        jdbc.update("update account set balance = 0 where id = 1");
    }

    @Test
    void oneUserCannotOwnTwoWallets() {
        long userId = newUser();
        jdbc.update("insert into account (type, owner_user_id) values ('USER_WALLET', ?)", userId);

        assertThatThrownBy(() ->
                jdbc.update("insert into account (type, owner_user_id) values ('USER_WALLET', ?)", userId))
                .hasStackTraceContaining("uq_wallet_owner");
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        long tx = newTransaction();
        long wallet = newWallet();
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, 1, -10)", tx);
            jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, ?, 10)", tx, wallet);
        });

        assertThatThrownBy(() -> jdbc.update("update ledger_entry set amount = 999 where transaction_id = ?", tx))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entry where transaction_id = ?", tx))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void anUnbalancedTransactionIsRejectedAtCommit() {
        long tx = newTransaction();
        long wallet = newWallet();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, ?, 10)",
                        tx, wallet)))
                .hasStackTraceContaining("Unbalanced transaction");
    }

    @Test
    void aBalancedTransactionCommitsAndTheLedgerSumsToZero() {
        long tx = newTransaction();
        long wallet = newWallet();

        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, 1, -25)", tx);
            jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, ?, 25)", tx, wallet);
        });

        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entry", BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    @Test
    void aTransactionCannotBeReversedTwice() {
        long original = newTransaction();
        Long userId = jdbc.queryForObject(
                "select initiated_by_user_id from ledger_transaction where id = ?", Long.class, original);
        jdbc.update("insert into ledger_transaction (public_id, type, reverses_transaction_id, initiated_by_user_id) "
                + "values (gen_random_uuid(), 'REVERSAL', ?, ?)", original, userId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into ledger_transaction (public_id, type, reverses_transaction_id, initiated_by_user_id) "
                        + "values (gen_random_uuid(), 'REVERSAL', ?, ?)", original, userId))
                .hasStackTraceContaining("ledger_transaction_reverses_transaction_id_key");
    }
}
