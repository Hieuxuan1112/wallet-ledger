package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two raw JDBC connections, not two Spring-managed transactions — the two sides of a phenomenon
 * need independent control over isolation level and exactly when each commits, which
 * {@code @Transactional} doesn't give a single test method.
 * <p>
 * PostgreSQL's REPEATABLE READ is stricter than the SQL standard requires: the standard only
 * promises it stops non-repeatable reads, but PostgreSQL implements it as a snapshot taken at the
 * transaction's first statement, which also stops phantom reads — something the standard leaves
 * optional at this level. An answer that only quotes the standard's table undersells what the
 * database actually does.
 */
class IsolationPhenomenaIT extends AbstractIntegrationTest {

    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private DataSource dataSource;

    private long seedWallet(BigDecimal balance) {
        AppUser user = users.save(AppUser.create("iso-" + java.util.UUID.randomUUID(), "hash"));
        Account wallet = accounts.saveAndFlush(Account.walletFor(user.getId()));
        wallet.credit(balance);
        return accounts.saveAndFlush(wallet).getId();
    }

    private BigDecimal readBalance(Connection conn, long walletId) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select balance from account where id = " + walletId)) {
            rs.next();
            return rs.getBigDecimal("balance");
        }
    }

    @Test
    void nonRepeatableReadOccursAtReadCommitted() throws Exception {
        long walletId = seedWallet(new BigDecimal("100.0000"));

        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            a.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            BigDecimal firstRead = readBalance(a, walletId);

            b.setAutoCommit(false);
            b.createStatement().executeUpdate(
                    "update account set balance = 50.0000 where id = " + walletId);
            b.commit();

            BigDecimal secondRead = readBalance(a, walletId);
            a.commit();

            assertThat(firstRead).isEqualByComparingTo("100.0000");
            // The point of the test: the SAME transaction, the SAME query, two different answers.
            assertThat(secondRead).isEqualByComparingTo("50.0000");
        }
    }

    @Test
    void repeatableReadPreventsIt() throws Exception {
        long walletId = seedWallet(new BigDecimal("100.0000"));

        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            a.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            BigDecimal firstRead = readBalance(a, walletId);

            b.setAutoCommit(false);
            b.createStatement().executeUpdate(
                    "update account set balance = 50.0000 where id = " + walletId);
            b.commit();

            BigDecimal secondRead = readBalance(a, walletId);
            a.commit();

            // Both reads see the snapshot as of A's first statement — B's committed change is
            // simply invisible until A starts a new transaction.
            assertThat(firstRead).isEqualByComparingTo("100.0000");
            assertThat(secondRead).isEqualByComparingTo("100.0000");
        }
    }
}
