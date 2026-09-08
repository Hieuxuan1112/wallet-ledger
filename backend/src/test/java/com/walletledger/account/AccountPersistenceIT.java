package com.walletledger.account;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPersistenceIT extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Test
    void aNewWalletStartsAtZeroAndVersionZero() {
        AppUser user = users.save(AppUser.create("dave", "hash"));

        Account wallet = accounts.save(Account.walletFor(user.getId()));

        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getVersion()).isZero();
        assertThat(wallet.getType()).isEqualTo(AccountType.USER_WALLET);
    }

    @Test
    void updatingABalanceIncrementsTheVersion() {
        AppUser user = users.save(AppUser.create("erin", "hash"));
        Account wallet = accounts.saveAndFlush(Account.walletFor(user.getId()));

        wallet.credit(new BigDecimal("100.0000"));
        Account saved = accounts.saveAndFlush(wallet);

        assertThat(saved.getBalance()).isEqualByComparingTo("100.0000");
        assertThat(saved.getVersion()).isEqualTo(1L);
    }

    @Test
    void systemAccountsAreFindableByType() {
        assertThat(accounts.findByType(AccountType.SYSTEM_FUNDING)).isPresent();
        assertThat(accounts.findByType(AccountType.SYSTEM_PAYOUT)).isPresent();
    }

    @Test
    void debitingBelowZeroIsRefusedByTheEntityBeforeTheDatabaseSeesIt() {
        AppUser user = users.save(AppUser.create("frida", "hash"));
        Account wallet = accounts.saveAndFlush(Account.walletFor(user.getId()));

        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> wallet.debit(new BigDecimal("1.0000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative");
    }
}
