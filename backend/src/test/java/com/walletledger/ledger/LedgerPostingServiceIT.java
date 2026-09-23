package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingServiceIT extends AbstractIntegrationTest {

    private static final long SYSTEM_FUNDING = 1L;
    private static final long SYSTEM_PAYOUT = 2L;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private LedgerPostingService posting;

    @Autowired
    private JdbcTemplate jdbc;

    private Account newWallet() {
        AppUser user = users.save(AppUser.create("post-" + UUID.randomUUID(), "hash"));
        return accounts.save(Account.walletFor(user.getId()));
    }

    @Test
    void postingMovesTheCachedBalanceAndWritesTwoEntries() {
        Account wallet = newWallet();

        LedgerTransaction tx = posting.post(TransactionType.DEPOSIT, wallet.getOwnerUserId(),
                "top up", SYSTEM_FUNDING, wallet.getId(), new BigDecimal("50.0000"));

        assertThat(accounts.findById(wallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry where transaction_id = ?", Integer.class, tx.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select sum(amount) from ledger_entry where transaction_id = ?", BigDecimal.class, tx.getId()))
                .isEqualByComparingTo("0");
    }

    @Test
    void theCachedBalanceAlwaysEqualsTheSumOfEntries() {
        Account wallet = newWallet();
        posting.post(TransactionType.DEPOSIT, wallet.getOwnerUserId(), "one",
                SYSTEM_FUNDING, wallet.getId(), new BigDecimal("30.0000"));
        posting.post(TransactionType.DEPOSIT, wallet.getOwnerUserId(), "two",
                SYSTEM_FUNDING, wallet.getId(), new BigDecimal("12.5000"));

        BigDecimal cached = accounts.findById(wallet.getId()).orElseThrow().getBalance();
        BigDecimal derived = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry where account_id = ?",
                BigDecimal.class, wallet.getId());

        assertThat(cached).isEqualByComparingTo(derived);
    }

    @Test
    void aWalletCannotBeDrawnBelowZero() {
        Account wallet = newWallet();

        assertThatThrownBy(() -> posting.post(TransactionType.WITHDRAWAL, wallet.getOwnerUserId(),
                "too much", wallet.getId(), SYSTEM_PAYOUT, new BigDecimal("1.0000")))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(accounts.findById(wallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void nothingIsWrittenWhenTheOperationIsRefused() {
        Account wallet = newWallet();

        assertThatThrownBy(() -> posting.post(TransactionType.WITHDRAWAL, wallet.getOwnerUserId(),
                "too much", wallet.getId(), SYSTEM_PAYOUT, new BigDecimal("5.0000")))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry where account_id = ?", Integer.class, wallet.getId()))
                .isZero();
    }

    /**
     * The chokepoint has to defend itself. A negative amount reverses the direction of the
     * posting, so without this guard "transfer -50 to the victim" is a withdrawal FROM the
     * victim, and ck_wallet_non_negative does not object as long as the victim stays solvent.
     * The HTTP DTOs reject negatives, but MoneyService and this class are both callable
     * without them — Phase 4's AI layer is planned to be exactly such a caller.
     */
    @Test
    void aNegativeAmountCannotDrainTheOtherAccount() {
        Account attacker = newWallet();
        Account victim = newWallet();
        posting.post(TransactionType.DEPOSIT, victim.getOwnerUserId(), "seed",
                SYSTEM_FUNDING, victim.getId(), new BigDecimal("50.0000"));

        assertThatThrownBy(() -> posting.post(TransactionType.TRANSFER, attacker.getOwnerUserId(),
                "drain", attacker.getId(), victim.getId(), new BigDecimal("-50.0000")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(accounts.findById(victim.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("50.0000");
        assertThat(accounts.findById(attacker.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void aZeroAmountIsRefused() {
        Account wallet = newWallet();

        assertThatThrownBy(() -> posting.post(TransactionType.DEPOSIT, wallet.getOwnerUserId(),
                "nothing", SYSTEM_FUNDING, wallet.getId(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAccountCannotPayItself() {
        Account wallet = newWallet();

        assertThatThrownBy(() -> posting.post(TransactionType.TRANSFER, wallet.getOwnerUserId(),
                "loop", wallet.getId(), wallet.getId(), new BigDecimal("1.0000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
