package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyServiceIT extends AbstractIntegrationTest {

    @Autowired
    private MoneyService money;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private AppUser newUserWithWallet() {
        AppUser user = users.save(AppUser.create("money-" + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        return user;
    }

    private BigDecimal balanceOf(long accountId) {
        return jdbc.queryForObject("select balance from account where id = ?", BigDecimal.class, accountId);
    }

    private BigDecimal walletBalanceOf(AppUser user) {
        return accounts.findByOwnerUserId(user.getId()).orElseThrow().getBalance();
    }

    @Test
    void aDepositCreditsTheWalletAndDrawsOnSystemFunding() {
        AppUser user = newUserWithWallet();
        BigDecimal fundingBefore = balanceOf(1L);

        TransactionView view = money.deposit(user.getId(), new BigDecimal("50.0000"), "salary");

        assertThat(view.amount()).isEqualByComparingTo("50.0000");
        assertThat(view.balanceAfter()).isEqualByComparingTo("50.0000");
        assertThat(balanceOf(1L)).isEqualByComparingTo(fundingBefore.subtract(new BigDecimal("50.0000")));
    }

    @Test
    void aWithdrawalDebitsTheWalletAndCreditsSystemPayout() {
        AppUser user = newUserWithWallet();
        money.deposit(user.getId(), new BigDecimal("40.0000"), "seed");
        BigDecimal payoutBefore = balanceOf(2L);

        TransactionView view = money.withdraw(user.getId(), new BigDecimal("15.0000"), "cash out");

        assertThat(view.balanceAfter()).isEqualByComparingTo("25.0000");
        assertThat(balanceOf(2L)).isEqualByComparingTo(payoutBefore.add(new BigDecimal("15.0000")));
    }

    @Test
    void withdrawingMoreThanTheBalanceIsRefusedAndChangesNothing() {
        AppUser user = newUserWithWallet();
        money.deposit(user.getId(), new BigDecimal("10.0000"), "seed");

        assertThatThrownBy(() -> money.withdraw(user.getId(), new BigDecimal("10.0001"), "greedy"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletBalanceOf(user)).isEqualByComparingTo("10.0000");
    }

    @Test
    void aTransferMovesMoneyBetweenTwoWallets() {
        AppUser payer = newUserWithWallet();
        AppUser payee = newUserWithWallet();
        money.deposit(payer.getId(), new BigDecimal("100.0000"), "seed");

        TransactionView view = money.transfer(payer.getId(), payee.getUsername(),
                new BigDecimal("30.0000"), "lunch");

        assertThat(view.balanceAfter()).isEqualByComparingTo("70.0000");
        assertThat(walletBalanceOf(payee)).isEqualByComparingTo("30.0000");
    }

    @Test
    void transferringToYourselfIsRejected() {
        AppUser user = newUserWithWallet();
        money.deposit(user.getId(), new BigDecimal("10.0000"), "seed");

        assertThatThrownBy(() -> money.transfer(user.getId(), user.getUsername(),
                new BigDecimal("1.0000"), "loop"))
                .isInstanceOf(SelfTransferException.class);
    }

    @Test
    void transferringToAnUnknownUserIsRejected() {
        AppUser user = newUserWithWallet();
        money.deposit(user.getId(), new BigDecimal("10.0000"), "seed");

        assertThatThrownBy(() -> money.transfer(user.getId(), "nobody-" + UUID.randomUUID(),
                new BigDecimal("1.0000"), "void"))
                .isInstanceOf(RecipientNotFoundException.class);
    }

    /**
     * Deliberately a snapshot rather than an assertion that the total is zero. One PostgreSQL
     * container serves the whole JVM and nothing rolls back between classes, so by the time this
     * class runs the total is already non-zero: AccountPersistenceIT credits a wallet through the
     * entity with no ledger entry behind it, and LedgerSchemaIT overwrites the SYSTEM_FUNDING
     * balance with raw SQL. Both are doing their job. The invariant that belongs to MoneyService
     * is that a run of deposits, transfers and withdrawals creates and destroys nothing, and a
     * delta states exactly that without depending on what any other class left behind.
     */
    private BigDecimal totalAccountBalance() {
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from account", BigDecimal.class);
    }

    @Test
    void moneyIsNeitherCreatedNorDestroyed() {
        AppUser a = newUserWithWallet();
        AppUser b = newUserWithWallet();
        BigDecimal balancesBefore = totalAccountBalance();

        money.deposit(a.getId(), new BigDecimal("70.0000"), "seed");
        money.transfer(a.getId(), b.getUsername(), new BigDecimal("20.0000"), "split");
        money.withdraw(b.getId(), new BigDecimal("5.0000"), "cash");

        // The ledger total, unlike the balance total, IS globally zero: the deferred constraint
        // trigger from V2 refuses any transaction that does not balance at commit.
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entry", BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(totalAccountBalance()).isEqualByComparingTo(balancesBefore);
    }
}
