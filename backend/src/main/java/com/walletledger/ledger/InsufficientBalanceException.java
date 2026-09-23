package com.walletledger.ledger;

/**
 * Plain domain signal: the ledger package must not depend on Spring Web (see
 * ArchitectureRulesTest#theLedgerDoesNotDependOnTheWebLayer), so this carries no HTTP status.
 * MoneyService translates it into the HTTP-aware money.InsufficientFundsException.
 */
public class InsufficientBalanceException extends RuntimeException {
}
