package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.LedgerTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundServiceIT extends AbstractIntegrationTest {

    @Autowired private RefundService refunds;
    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerTransactionRepository transactions;

    private AppUser userWithWallet(String prefix) {
        AppUser user = users.save(AppUser.create(prefix + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        return user;
    }

    @Test
    void refundingATransferReturnsTheMoney() {
        AppUser alice = userWithWallet("alice-");
        AppUser bob = userWithWallet("bob-");
        money.deposit(alice.getId(), new BigDecimal("100.0000"), "seed");

        TransactionView transfer = money.transfer(alice.getId(), bob.getUsername(),
                new BigDecimal("30.0000"), "rent");

        refunds.refund(alice.getId(), transfer.transactionId(), "changed my mind");

        BigDecimal aliceBalance = accounts.findByOwnerUserId(alice.getId()).orElseThrow().getBalance();
        BigDecimal bobBalance = accounts.findByOwnerUserId(bob.getId()).orElseThrow().getBalance();
        assertThat(aliceBalance).isEqualByComparingTo("100.0000");
        assertThat(bobBalance).isEqualByComparingTo("0.0000");
    }

    @Test
    void onlyTheInitiatorMayRefund() {
        AppUser alice = userWithWallet("alice-");
        AppUser bob = userWithWallet("bob-");
        money.deposit(alice.getId(), new BigDecimal("50.0000"), "seed");
        TransactionView transfer = money.transfer(alice.getId(), bob.getUsername(),
                new BigDecimal("10.0000"), "gift");

        assertThatThrownBy(() -> refunds.refund(bob.getId(), transfer.transactionId(), "not mine"))
                .isInstanceOf(NotTheInitiatorException.class);
    }

    @Test
    void aTransactionCannotBeRefundedTwice() {
        AppUser alice = userWithWallet("alice-");
        money.deposit(alice.getId(), new BigDecimal("20.0000"), "seed");
        TransactionView deposit = money.deposit(alice.getId(), new BigDecimal("5.0000"), "second");

        refunds.refund(alice.getId(), deposit.transactionId(), "first refund");

        assertThatThrownBy(() -> refunds.refund(alice.getId(), deposit.transactionId(), "second refund"))
                .isInstanceOf(AlreadyRefundedException.class);
    }

    @Test
    void aReversalCannotItselfBeRefunded() {
        AppUser alice = userWithWallet("alice-");
        money.deposit(alice.getId(), new BigDecimal("15.0000"), "seed");
        TransactionView deposit = money.deposit(alice.getId(), new BigDecimal("5.0000"), "to refund");
        TransactionView reversal = refunds.refund(alice.getId(), deposit.transactionId(), "undo");

        assertThatThrownBy(() -> refunds.refund(alice.getId(), reversal.transactionId(), "undo the undo"))
                .isInstanceOf(CannotRefundAReversalException.class);
    }

    @Test
    void aMissingTransactionIsRefused() {
        AppUser alice = userWithWallet("alice-");

        assertThatThrownBy(() -> refunds.refund(alice.getId(), UUID.randomUUID(), "ghost"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void refundIsRefusedIfTheCounterpartyAlreadySpentIt() {
        AppUser alice = userWithWallet("alice-");
        AppUser bob = userWithWallet("bob-");
        money.deposit(alice.getId(), new BigDecimal("40.0000"), "seed");
        TransactionView transfer = money.transfer(alice.getId(), bob.getUsername(),
                new BigDecimal("40.0000"), "all of it");
        // Bob spends it before Alice tries to claw it back.
        money.withdraw(bob.getId(), new BigDecimal("40.0000"), "spent");

        assertThatThrownBy(() -> refunds.refund(alice.getId(), transfer.transactionId(), "too late"))
                .isInstanceOf(InsufficientFundsException.class);
    }
}
