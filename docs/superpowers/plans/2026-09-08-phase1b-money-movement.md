# Phase 1B — Money Movement and Pessimistic Locking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make money move correctly under concurrency — deposit, withdraw and transfer, each idempotent, each posting a balanced pair of ledger entries — and prove it with 100 threads withdrawing from one wallet and with simultaneous A→B and B→A transfers that must not deadlock.

**Architecture:** Every money operation funnels through one primitive, `LedgerPostingService.post`, which locks the two affected accounts with `SELECT ... FOR UPDATE` **in ascending account id order**, adjusts both cached balances, and writes exactly two ledger entries inside a single READ COMMITTED transaction. Idempotency is a row in the same transaction, claimed by a unique constraint. Audit logging runs in its own transaction so a refused operation still leaves a trail.

**Tech Stack:** Unchanged from Phase 1A — Java 21, Spring Boot 3.5.16, Spring Data JPA, PostgreSQL 16, Flyway, JUnit 5, Testcontainers 1.21.4. No new dependencies.

**Starting point:** Phase 1A complete — 34 tests green, migrations V1–V3 applied, auth and wallet working, `Account` mapped with a cached `balance` and a `@Version` column that nothing uses yet.

**Scope boundary:** The alternative locking strategies (optimistic, serializable, deliberately unsafe), the isolation-phenomenon reproductions, the ledger-invariant fuzz test, ArchUnit rules and the JaCoCo gate are Phase 1C. Statement, refund, outbox and Kafka are Phase 2.

---

## Environment

Unchanged. Paste once per PowerShell session:

```powershell
function mvnd { docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" -v "//var/run/docker.sock:/var/run/docker.sock" -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true --add-host host.docker.internal:host-gateway -w /app maven:3.9-eclipse-temurin-21 mvn @args }
```

Every `-D` argument must be quoted — PowerShell splits `-Dit.test=Foo` at the dot and Maven then reports `Unknown lifecycle phase ".test=Foo"`.

**Git:** the repository owner runs every `git` write command themselves, in **cmd.exe**. Commit commands are therefore written as a single line with repeated `-m` flags.

---

## File structure

| File | Responsibility |
|---|---|
| `db/migration/V4__idempotency_and_audit.sql` | `idempotency_key`, `audit_log` |
| `ledger/TransactionType.java` | DEPOSIT / WITHDRAWAL / TRANSFER / REVERSAL |
| `ledger/LedgerTransaction.java`, `ledger/LedgerEntry.java` | Ledger entities |
| `ledger/LedgerTransactionRepository.java`, `ledger/LedgerEntryRepository.java` | Ledger persistence |
| `ledger/LedgerPostingService.java` | **The only place money moves.** Ordered locking, balance update, entry pair |
| `ledger/InsufficientFundsException.java` | 409 |
| `money/TransactionView.java` | Response DTO shared by all three endpoints |
| `money/AmountRequest.java`, `money/TransferRequest.java` | Request DTOs with amount validation |
| `money/MoneyController.java` | The three endpoints |
| `money/MoneyService.java` | Deposit, withdraw, transfer in terms of `LedgerPostingService` |
| `idempotency/IdempotencyRecord.java`, `...Repository.java` | The claimed-key row |
| `idempotency/IdempotencyService.java` | Orchestration: replay, conflict, in-progress |
| `idempotency/IdempotentExecutor.java` | The transactional half: claim key, run, record response |
| `audit/AuditLogger.java` | `REQUIRES_NEW`, own bean |

`LedgerPostingService` is the chokepoint. If a future feature needs to move money and does not go through it, that is a design error, not a shortcut.

---

## Task 1: Idempotency and audit schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__idempotency_and_audit.sql`
- Test: `backend/src/test/java/com/walletledger/idempotency/IdempotencySchemaIT.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=IdempotencySchemaIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL with `relation "idempotency_key" does not exist`.

- [ ] **Step 3: Write `V4__idempotency_and_audit.sql`**

`response_status` and `response_body` are nullable because the row is written in two moments: the key is claimed as the operation starts, and the response is recorded as it finishes. A row with a null body is therefore an operation still in flight, which is exactly what the concurrent-duplicate case needs to detect.

```sql
CREATE TABLE idempotency_key (
    id              BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES app_user (id),
    idem_key        VARCHAR(64) NOT NULL,
    endpoint        VARCHAR(64) NOT NULL,
    request_hash    VARCHAR(64)   NOT NULL,
    response_status INT           NULL,
    -- VARCHAR, not TEXT: Hibernate maps String to VARCHAR and ddl-auto=validate compares the
    -- JDBC type. A transaction response is a few hundred bytes, so 4096 is generous.
    response_body   VARCHAR(4096) NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key)
);

CREATE TABLE audit_log (
    id         BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NULL REFERENCES app_user (id),
    action     VARCHAR(64) NOT NULL,
    detail     VARCHAR(1000),
    outcome    VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX idx_audit_user_created ON audit_log (user_id, created_at DESC);
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=IdempotencySchemaIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V4__idempotency_and_audit.sql backend/src/test/java/com/walletledger/idempotency
```

```bash
git commit -m "feat: add idempotency and audit schema with Flyway V4" -m "The idempotency row is claimed when an operation starts and completed when it finishes, so response_status and response_body are nullable and a null body means the operation is still in flight. UNIQUE (user_id, idem_key) scopes keys per user, so two clients choosing the same key never collide."
```

---

## Task 2: Ledger entities

**Files:**
- Create: `backend/src/main/java/com/walletledger/ledger/TransactionType.java`
- Create: `backend/src/main/java/com/walletledger/ledger/LedgerTransaction.java`
- Create: `backend/src/main/java/com/walletledger/ledger/LedgerEntry.java`
- Create: `backend/src/main/java/com/walletledger/ledger/LedgerTransactionRepository.java`
- Create: `backend/src/main/java/com/walletledger/ledger/LedgerEntryRepository.java`
- Test: `backend/src/test/java/com/walletledger/ledger/LedgerEntityIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerEntityIT extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private LedgerTransactionRepository transactions;

    @Autowired
    private LedgerEntryRepository entries;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void aBalancedPairPersists() {
        AppUser user = users.save(AppUser.create("ledger-a-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));

        LedgerTransaction saved = transactionTemplate.execute(status -> {
            LedgerTransaction tx = transactions.save(
                    LedgerTransaction.of(TransactionType.DEPOSIT, user.getId(), "test deposit"));
            entries.save(new LedgerEntry(tx.getId(), 1L, new BigDecimal("-25.0000")));
            entries.save(new LedgerEntry(tx.getId(), wallet.getId(), new BigDecimal("25.0000")));
            return tx;
        });

        assertThat(saved.getPublicId()).isNotNull();
        assertThat(entries.findByTransactionId(saved.getId())).hasSize(2);
    }

    @Test
    void anUnbalancedPairIsStillRejectedAtCommitThroughJpa() {
        AppUser user = users.save(AppUser.create("ledger-b-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));

        // The deferred constraint trigger does not care that the write came from Hibernate.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            LedgerTransaction tx = transactions.save(
                    LedgerTransaction.of(TransactionType.DEPOSIT, user.getId(), "broken"));
            entries.save(new LedgerEntry(tx.getId(), wallet.getId(), new BigDecimal("25.0000")));
        })).hasStackTraceContaining("Unbalanced transaction");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=LedgerEntityIT" "-DfailIfNoSpecifiedTests=false"`

Expected: compilation failure — none of the ledger classes exist yet.

- [ ] **Step 3: Write `TransactionType`**

```java
package com.walletledger.ledger;

public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    REVERSAL
}
```

- [ ] **Step 4: Write `LedgerTransaction`**

`publicId` is what the API exposes. A sequence id would leak how many transactions the system has processed and would let anyone guess neighbouring ids.

```java
package com.walletledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Column(name = "reverses_transaction_id")
    private Long reversesTransactionId;

    @Column(name = "initiated_by_user_id", nullable = false)
    private Long initiatedByUserId;

    @Column(length = 255)
    private String description;

    protected LedgerTransaction() {
    }

    private LedgerTransaction(TransactionType type, Long initiatedByUserId, String description) {
        this.publicId = UUID.randomUUID();
        this.type = type;
        this.initiatedByUserId = initiatedByUserId;
        this.description = description;
    }

    public static LedgerTransaction of(TransactionType type, Long initiatedByUserId, String description) {
        return new LedgerTransaction(type, initiatedByUserId, description);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public TransactionType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }
}
```

- [ ] **Step 5: Write `LedgerEntry`**

Plain foreign-key columns rather than `@ManyToOne`. A ledger is written once and read as a list; there is no navigation to justify the lazy-loading proxies, and keeping ids makes the entity trivially safe to create inside a locked section.

```java
package com.walletledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Signed. Negative leaves the account, positive arrives. The pair must sum to zero. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected LedgerEntry() {
    }

    public LedgerEntry(Long transactionId, Long accountId, BigDecimal amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
    }

    public Long getAccountId() {
        return accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
```

- [ ] **Step 6: Write the repositories**

`backend/src/main/java/com/walletledger/ledger/LedgerTransactionRepository.java`

```java
package com.walletledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByPublicId(UUID publicId);
}
```

`backend/src/main/java/com/walletledger/ledger/LedgerEntryRepository.java`

```java
package com.walletledger.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(Long transactionId);
}
```

- [ ] **Step 7: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=LedgerEntityIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 2, Failures: 0`.

A `Schema-validation` failure means an entity does not match V2; fix the entity, never the migration.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/walletledger/ledger backend/src/test/java/com/walletledger/ledger/LedgerEntityIT.java
```

```bash
git commit -m "feat: add ledger transaction and entry entities" -m "Entries hold plain foreign-key columns rather than @ManyToOne associations: a ledger is written once and read as a list, so there is no navigation to justify lazy proxies, and plain ids keep entity creation trivially safe inside a locked section. The API exposes a UUID public_id rather than the sequence id, which would otherwise leak transaction volume and let anyone guess neighbouring records. A test writes an unbalanced pair through Hibernate to confirm the deferred constraint trigger does not care where the write came from."
```

---

## Task 3: The posting primitive with ordered locking

This is the task the whole project exists for. Everything after it is an application of it.

**Files:**
- Modify: `backend/src/main/java/com/walletledger/account/AccountRepository.java` — add the locking finder
- Create: `backend/src/main/java/com/walletledger/ledger/InsufficientFundsException.java`
- Create: `backend/src/main/java/com/walletledger/ledger/LedgerPostingService.java`
- Test: `backend/src/test/java/com/walletledger/ledger/LedgerPostingServiceIT.java`

- [ ] **Step 1: Write the failing test**

```java
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
                "too much", wallet.getId(), 2L, new BigDecimal("1.0000")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(accounts.findById(wallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0");
    }

    @Test
    void nothingIsWrittenWhenTheOperationIsRefused() {
        Account wallet = newWallet();

        assertThatThrownBy(() -> posting.post(TransactionType.WITHDRAWAL, wallet.getOwnerUserId(),
                "too much", wallet.getId(), 2L, new BigDecimal("5.0000")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry where account_id = ?", Integer.class, wallet.getId()))
                .isZero();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=LedgerPostingServiceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: compilation failure — `LedgerPostingService` and `InsufficientFundsException` do not exist.

- [ ] **Step 3: Add the locking finder to `AccountRepository`**

Written as an explicit `@Query` rather than a derived method. A derived name hides the SQL it generates, and this is the one query in the system whose exact shape decides whether money can be lost.

Add these imports and the method:

```java
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
```

```java
    /** Emits SELECT ... FOR UPDATE. The caller must lock in ascending id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
```

- [ ] **Step 4: Write `InsufficientFundsException`**

The message deliberately omits the balance and the shortfall. For a transfer the payer is the caller so it would be harmless, but the same exception will be thrown for refunds in Phase 2, where the account being drawn belongs to somebody else.

```java
package com.walletledger.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InsufficientFundsException extends ErrorResponseException {

    public InsufficientFundsException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Insufficient funds");
        detail.setDetail("The account does not hold enough to complete this operation");
        return detail;
    }
}
```

- [ ] **Step 5: Write `LedgerPostingService`**

```java
package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class LedgerPostingService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;

    public LedgerPostingService(AccountRepository accounts,
                                LedgerTransactionRepository transactions,
                                LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    /**
     * The only place in the application where a balance changes.
     * <p>
     * Runs at READ COMMITTED, PostgreSQL's default, and does not rely on it: correctness comes
     * from holding a row lock on both accounts for the whole read-modify-write, not from the
     * isolation level. The locks are taken in ascending account id order, which is what stops
     * A→B and B→A deadlocking — with a fixed global order two transactions can never each hold
     * what the other wants next.
     *
     * @param fromAccountId the account money leaves; receives the negative entry
     * @param toAccountId   the account money arrives in; receives the positive entry
     */
    @Transactional
    public LedgerTransaction post(TransactionType type, long initiatedByUserId, String description,
                                  long fromAccountId, long toAccountId, BigDecimal amount) {
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("An account cannot pay itself");
        }

        // Two separate statements, lower id first. A single "where id in (a, b) order by id
        // for update" would NOT do: PostgreSQL makes no promise about the order in which it
        // acquires row locks, so only explicit sequential locking makes the ordering real.
        long firstId = Math.min(fromAccountId, toAccountId);
        long secondId = Math.max(fromAccountId, toAccountId);
        Account first = lock(firstId);
        Account second = lock(secondId);

        Account from = firstId == fromAccountId ? first : second;
        Account to = firstId == toAccountId ? first : second;

        try {
            from.debit(amount);
        } catch (IllegalStateException e) {
            throw new InsufficientFundsException();
        }
        to.credit(amount);

        LedgerTransaction transaction =
                transactions.save(LedgerTransaction.of(type, initiatedByUserId, description));
        entries.save(new LedgerEntry(transaction.getId(), from.getId(), amount.negate()));
        entries.save(new LedgerEntry(transaction.getId(), to.getId(), amount));

        return transaction;
    }

    private Account lock(long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
```

- [ ] **Step 6: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=LedgerPostingServiceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 7: Confirm the lock is actually being taken**

A passing test does not prove the lock reached the database. Run once with SQL logging and read
the statement. Use the logging level, **not** `spring.jpa.show-sql`, which does not take effect
through a `-D` here:

Run: `mvnd -B verify "-Dit.test=LedgerPostingServiceIT" "-DfailIfNoSpecifiedTests=false" "-Dlogging.level.org.hibernate.SQL=DEBUG"`

Expected: the account selects end in **`for no key update`**, not `for update`. Hibernate 6 maps
`PESSIMISTIC_WRITE` to PostgreSQL's `FOR NO KEY UPDATE`, which still conflicts with another
`FOR NO KEY UPDATE` on the same row — so two balance updaters still exclude each other — while
leaving `FOR KEY SHARE` free. That matters: `FOR KEY SHARE` is what PostgreSQL takes on an
`account` row when somebody inserts a `ledger_entry` referencing it, so the weaker lock is the
better one here. Do not "fix" it to `FOR UPDATE`.

If **no** SQL appears at all, the logging is off, not the code. Grep for `insert` too before
concluding anything about the lock.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/ledger/LedgerPostingServiceIT.java
```

```bash
git commit -m "feat: add the ledger posting primitive with ordered pessimistic locking" -m "One chokepoint for every balance change. Both accounts are locked with SELECT ... FOR UPDATE before the read-modify-write, in ascending account id order, which is what prevents A-to-B and B-to-A from deadlocking: with one global ordering two transactions can never each hold what the other needs next. The locks are two separate statements rather than one where-id-in-order-by, because PostgreSQL makes no promise about the order in which it acquires row locks within a single statement." -m "Correctness does not depend on the isolation level. It comes from holding the row locks across the whole operation, which is why READ COMMITTED is enough. InsufficientFundsException carries no balance figure, because the same exception will be raised in Phase 2 for refunds where the drawn account belongs to somebody else."
```

---

## Task 4: Deposit, withdrawal and transfer at the service layer

No HTTP yet. Getting the money rules right is a separate problem from exposing them.

**Files:**
- Modify: `backend/src/main/java/com/walletledger/account/AccountRepository.java` — add a wallet-id projection
- Create: `backend/src/main/java/com/walletledger/money/TransactionView.java`
- Create: `backend/src/main/java/com/walletledger/money/RecipientNotFoundException.java`
- Create: `backend/src/main/java/com/walletledger/money/SelfTransferException.java`
- Create: `backend/src/main/java/com/walletledger/money/MoneyService.java`
- Test: `backend/src/test/java/com/walletledger/money/MoneyServiceIT.java`

**A hazard this task is designed around.** The obvious shape is to load the caller's wallet, call
`post`, then read `wallet.getBalance()`. That is unsafe: the entity would enter the persistence
context from an **unlocked** read, and acquiring a pessimistic lock on an already-managed instance
does not necessarily refresh its state. The balance driving the decision could then be stale by the
time the lock is held — precisely the class of bug this project exists to prevent. So `MoneyService`
never loads an `Account`. It resolves the wallet **id** with a projection query, hands ids to
`LedgerPostingService`, and reads the balance afterwards from the instance the posting service
loaded under lock.

- [ ] **Step 1: Write the failing test**

```java
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

    @Test
    void theWholeLedgerStillSumsToZero() {
        AppUser a = newUserWithWallet();
        AppUser b = newUserWithWallet();
        money.deposit(a.getId(), new BigDecimal("70.0000"), "seed");
        money.transfer(a.getId(), b.getUsername(), new BigDecimal("20.0000"), "split");
        money.withdraw(b.getId(), new BigDecimal("5.0000"), "cash");

        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_entry", BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select coalesce(sum(balance), 0) from account", BigDecimal.class))
                .isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=MoneyServiceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: compilation failure — `MoneyService` and the two exceptions do not exist.

- [ ] **Step 3: Add the wallet-id projection to `AccountRepository`**

Selecting the id alone keeps the account out of the persistence context, which is the point
described above. The unique partial index from V2 guarantees at most one row per owner.

```java
    /** Returns the id only, deliberately: loading the entity here would be an unlocked read. */
    @Query("select a.id from Account a where a.ownerUserId = :userId")
    Optional<Long> findWalletIdByOwnerUserId(@Param("userId") Long userId);
```

- [ ] **Step 4: Write `TransactionView`**

```java
package com.walletledger.money;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.walletledger.ledger.LedgerTransaction;
import com.walletledger.ledger.TransactionType;

import java.math.BigDecimal;
import java.util.UUID;

/** Amounts are JSON strings for the same reason WalletView's balance is: JavaScript numbers. */
public record TransactionView(
        UUID transactionId,
        TransactionType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balanceAfter) {

    public static TransactionView of(LedgerTransaction transaction, BigDecimal amount, BigDecimal balanceAfter) {
        return new TransactionView(transaction.getPublicId(), transaction.getType(), amount, balanceAfter);
    }
}
```

- [ ] **Step 5: Write the two exceptions**

`backend/src/main/java/com/walletledger/money/RecipientNotFoundException.java`

A 404 here does reveal whether a username exists. That is accepted: a wallet application has to
let people address each other by name, and every consumer product in this space works the same
way. It is recorded as a deliberate trade-off rather than an oversight.

```java
package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class RecipientNotFoundException extends ErrorResponseException {

    public RecipientNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("Recipient not found");
        detail.setDetail("No wallet exists for that username");
        return detail;
    }
}
```

`backend/src/main/java/com/walletledger/money/SelfTransferException.java`

```java
package com.walletledger.money;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class SelfTransferException extends ErrorResponseException {

    public SelfTransferException() {
        super(HttpStatus.BAD_REQUEST, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Self transfer");
        detail.setDetail("A wallet cannot transfer to itself");
        return detail;
    }
}
```

- [ ] **Step 6: Write `MoneyService`**

```java
package com.walletledger.money;

import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.LedgerPostingService;
import com.walletledger.ledger.LedgerTransaction;
import com.walletledger.ledger.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class MoneyService {

    /**
     * Seeded with these exact ids by V2__ledger.sql, and LedgerSchemaIT keeps the constants
     * honest. Looking them up by type on every request would scan a table that grows with every
     * user, to learn something the migration already guarantees.
     */
    private static final long SYSTEM_FUNDING = 1L;
    private static final long SYSTEM_PAYOUT = 2L;

    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final LedgerPostingService posting;

    public MoneyService(AppUserRepository users, AccountRepository accounts, LedgerPostingService posting) {
        this.users = users;
        this.accounts = accounts;
        this.posting = posting;
    }

    @Transactional
    public TransactionView deposit(long userId, BigDecimal amount, String description) {
        long walletId = walletIdOf(userId);
        LedgerTransaction tx = posting.post(TransactionType.DEPOSIT, userId, description,
                SYSTEM_FUNDING, walletId, amount);
        return TransactionView.of(tx, amount, balanceAfter(walletId));
    }

    @Transactional
    public TransactionView withdraw(long userId, BigDecimal amount, String description) {
        long walletId = walletIdOf(userId);
        LedgerTransaction tx = posting.post(TransactionType.WITHDRAWAL, userId, description,
                walletId, SYSTEM_PAYOUT, amount);
        return TransactionView.of(tx, amount, balanceAfter(walletId));
    }

    @Transactional
    public TransactionView transfer(long fromUserId, String toUsername, BigDecimal amount, String description) {
        AppUser recipient = users.findByUsername(toUsername)
                .orElseThrow(RecipientNotFoundException::new);
        if (recipient.getId() == fromUserId) {
            throw new SelfTransferException();
        }
        long fromWalletId = walletIdOf(fromUserId);
        long toWalletId = walletIdOf(recipient.getId());

        LedgerTransaction tx = posting.post(TransactionType.TRANSFER, fromUserId, description,
                fromWalletId, toWalletId, amount);
        return TransactionView.of(tx, amount, balanceAfter(fromWalletId));
    }

    private long walletIdOf(long userId) {
        return accounts.findWalletIdByOwnerUserId(userId)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " has no wallet"));
    }

    /** Read after posting: this returns the instance the posting service loaded under lock. */
    private BigDecimal balanceAfter(long walletId) {
        return accounts.findById(walletId).orElseThrow().getBalance();
    }
}
```

`recipient.getId() == fromUserId` compares a `Long` against a `long`, which unboxes the left side —
correct here. Do not "tidy" it into a comparison of two boxed values, which would compare references.

- [ ] **Step 7: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=MoneyServiceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 7, Failures: 0`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/money
```

```bash
git commit -m "feat: add deposit, withdrawal and transfer at the service layer" -m "MoneyService never loads an Account. It resolves the wallet id with a projection query and lets LedgerPostingService do the only entity load, under lock. Loading the wallet first would put it in the persistence context from an unlocked read, and acquiring a pessimistic lock on an already-managed instance does not necessarily refresh its state, so the balance driving the decision could be stale by the time the lock is held." -m "System account ids are constants rather than a lookup by type: the migration seeds them and a schema test guards them, whereas findByType would scan a table that grows with every user to learn something already guaranteed. A 404 for an unknown recipient does reveal that a username exists, which is accepted deliberately, because addressing people by name is the point of the feature."
```

---

## Task 5: Idempotency

**The shape of the problem.** The idempotency row and the ledger entries must commit together —
that is the whole reason the key lives in PostgreSQL rather than Redis. But a unique-constraint
violation **aborts the PostgreSQL transaction**, so the losing writer cannot then read the winner's
row from inside the same transaction. The orchestration splits in two: a non-transactional service
that looks first and interprets what it finds, and a transactional executor that claims the key and
does the work.

**Files:**
- Create: `idempotency/IdempotencyRecord.java`, `IdempotencyRecordRepository.java`
- Create: `idempotency/IdempotencyInProgressException.java`, `IdempotencyKeyReusedException.java`
- Create: `idempotency/IdempotencyPayloadCodec.java`, `IdempotentExecutor.java`, `IdempotencyService.java`
- Test: `backend/src/test/java/com/walletledger/idempotency/IdempotencyServiceIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walletledger.idempotency;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import com.walletledger.ledger.InsufficientFundsException;
import com.walletledger.money.MoneyService;
import com.walletledger.money.TransactionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceIT extends AbstractIntegrationTest {

    private static final String ENDPOINT = "POST /api/v1/wallet/deposits";

    @Autowired private IdempotencyService idempotency;
    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private AppUser newUserWithWallet() {
        AppUser user = users.save(AppUser.create("idem-" + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        return user;
    }

    private BigDecimal balanceOf(AppUser user) {
        return accounts.findByOwnerUserId(user.getId()).orElseThrow().getBalance();
    }

    @Test
    void theSameKeyTwiceProducesOneTransactionAndReplaysTheResponse() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":\"25.0000\"}";

        TransactionView first = idempotency.execute(user.getId(), key, ENDPOINT, body,
                () -> money.deposit(user.getId(), new BigDecimal("25.0000"), "once"));
        TransactionView replay = idempotency.execute(user.getId(), key, ENDPOINT, body,
                () -> money.deposit(user.getId(), new BigDecimal("25.0000"), "once"));

        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(balanceOf(user)).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry e join account a on a.id = e.account_id "
                        + "where a.owner_user_id = ?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRejected() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();

        idempotency.execute(user.getId(), key, ENDPOINT, "{\"amount\":\"10.0000\"}",
                () -> money.deposit(user.getId(), new BigDecimal("10.0000"), "first"));

        assertThatThrownBy(() -> idempotency.execute(user.getId(), key, ENDPOINT, "{\"amount\":\"99.0000\"}",
                () -> money.deposit(user.getId(), new BigDecimal("99.0000"), "different")))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(balanceOf(user)).isEqualByComparingTo("10.0000");
    }

    @Test
    void twoUsersMayUseTheSameKeyIndependently() {
        AppUser a = newUserWithWallet();
        AppUser b = newUserWithWallet();
        String key = "shared-" + UUID.randomUUID();

        idempotency.execute(a.getId(), key, ENDPOINT, "{\"amount\":\"5.0000\"}",
                () -> money.deposit(a.getId(), new BigDecimal("5.0000"), "a"));
        idempotency.execute(b.getId(), key, ENDPOINT, "{\"amount\":\"7.0000\"}",
                () -> money.deposit(b.getId(), new BigDecimal("7.0000"), "b"));

        assertThat(balanceOf(a)).isEqualByComparingTo("5.0000");
        assertThat(balanceOf(b)).isEqualByComparingTo("7.0000");
    }

    /**
     * A failed operation must not consume its key. Recording failures too would mean a client
     * retrying after a transient error could never succeed, and retrying is what clients do.
     */
    @Test
    void aFailedOperationLeavesNoClaimedKeyBehind() {
        AppUser user = newUserWithWallet();
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.execute(user.getId(), key, ENDPOINT, "{\"amount\":\"5.0000\"}",
                () -> money.withdraw(user.getId(), new BigDecimal("5.0000"), "no funds")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from idempotency_key where user_id = ? and idem_key = ?",
                Integer.class, user.getId(), key)).isZero();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=IdempotencyServiceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: compilation failure — nothing in the `idempotency` package exists yet.

- [ ] **Step 3: Write `IdempotencyRecord`**

```java
package com.walletledger.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;

    @Column(nullable = false, length = 64)
    private String endpoint;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", length = 4096)
    private String responseBody;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(Long userId, String idemKey, String endpoint, String requestHash) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.endpoint = endpoint;
        this.requestHash = requestHash;
    }

    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    /** A row with no body is an operation still in flight. */
    public boolean isComplete() {
        return responseBody != null;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
```

- [ ] **Step 4: Write the repository**

```java
package com.walletledger.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndIdemKey(Long userId, String idemKey);
}
```

- [ ] **Step 5: Write the two exceptions**

`IdempotencyInProgressException.java` — 409:

```java
package com.walletledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The request holding this key has not committed, so its response cannot be read and must not be
 * guessed. Stripe answers the same situation the same way: tell the client to retry shortly.
 */
public class IdempotencyInProgressException extends ErrorResponseException {

    public IdempotencyInProgressException() {
        super(HttpStatus.CONFLICT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Request in progress");
        detail.setDetail("Another request with this Idempotency-Key is still running. Retry shortly.");
        return detail;
    }
}
```

`IdempotencyKeyReusedException.java` — 422:

```java
package com.walletledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class IdempotencyKeyReusedException extends ErrorResponseException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setTitle("Idempotency key reused");
        detail.setDetail("This Idempotency-Key was already used for a different request body");
        return detail;
    }
}
```

- [ ] **Step 6: Write `IdempotencyPayloadCodec`**

```java
package com.walletledger.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.money.TransactionView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class IdempotencyPayloadCodec {

    private final ObjectMapper objectMapper;

    public IdempotencyPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(TransactionView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a transaction view", e);
        }
    }

    public TransactionView decode(String json) {
        try {
            return objectMapper.readValue(json, TransactionView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored transaction view", e);
        }
    }

    public String hash(String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(requestBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
```

- [ ] **Step 7: Write `IdempotentExecutor`**

`saveAndFlush` forces the INSERT to hit the database now, so a duplicate key surfaces here where it
can be caught rather than at commit time where it would escape the caller.

```java
package com.walletledger.idempotency;

import com.walletledger.money.TransactionView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Component
public class IdempotentExecutor {

    private final IdempotencyRecordRepository records;
    private final IdempotencyPayloadCodec codec;

    public IdempotentExecutor(IdempotencyRecordRepository records, IdempotencyPayloadCodec codec) {
        this.records = records;
        this.codec = codec;
    }

    /**
     * Claims the key and performs the operation in one transaction, so the key and the ledger
     * entries commit together or not at all. If the operation throws, the claim rolls back with
     * it and the key is free again.
     */
    @Transactional
    public TransactionView claimAndRun(long userId, String key, String endpoint, String requestHash,
                                       Supplier<TransactionView> action) {
        IdempotencyRecord record = records.saveAndFlush(
                new IdempotencyRecord(userId, key, endpoint, requestHash));
        TransactionView view = action.get();
        record.complete(200, codec.encode(view));
        return view;
    }
}
```

- [ ] **Step 8: Write `IdempotencyService`**

Deliberately **not** `@Transactional`: it has to survive the executor's transaction being aborted by
a duplicate key, which is only possible from outside that transaction.

```java
package com.walletledger.idempotency;

import com.walletledger.money.TransactionView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository records;
    private final IdempotentExecutor executor;
    private final IdempotencyPayloadCodec codec;

    public IdempotencyService(IdempotencyRecordRepository records, IdempotentExecutor executor,
                              IdempotencyPayloadCodec codec) {
        this.records = records;
        this.executor = executor;
        this.codec = codec;
    }

    public TransactionView execute(long userId, String key, String endpoint, String requestBody,
                                   Supplier<TransactionView> action) {
        String requestHash = codec.hash(requestBody);

        Optional<IdempotencyRecord> existing = records.findByUserIdAndIdemKey(userId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        try {
            return executor.claimAndRun(userId, key, endpoint, requestHash, action);
        } catch (DataIntegrityViolationException e) {
            // Somebody claimed the key between our read and our insert. Their transaction has
            // not committed, so their response is not readable from here, and it must not be
            // guessed.
            throw new IdempotencyInProgressException();
        }
    }

    private TransactionView replay(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        if (!record.isComplete()) {
            throw new IdempotencyInProgressException();
        }
        return codec.decode(record.getResponseBody());
    }
}
```

- [ ] **Step 9: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=IdempotencyServiceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 4, Failures: 0`.

If deserialising `TransactionView` fails, check that `-parameters` is on — Jackson binds records by
component name, and the Spring Boot parent enables the flag already.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/walletledger/idempotency backend/src/test/java/com/walletledger/idempotency
```

```bash
git commit -m "feat: add Postgres-backed idempotency for money operations" -m "The key and the ledger entries commit in one transaction, which is the reason the key lives in PostgreSQL and not in Redis: two stores have no shared commit, so a failure between them either double-charges the customer or loses the request. A unique constraint on (user_id, idem_key) is what actually decides." -m "The orchestration is split because a unique violation aborts the PostgreSQL transaction, so the losing writer cannot read the winner's row from inside it. IdempotencyService is not transactional and interprets what it finds; IdempotentExecutor is, and claims the key. A concurrent duplicate therefore gets 409 rather than a guessed response. A failed operation rolls its claim back, so a client retrying after a rejection is not locked out of its own key."
```

---

## Task 6: The three HTTP endpoints

**Files:**
- Create: `money/AmountRequest.java`, `money/TransferRequest.java`, `money/MoneyController.java`
- Create: `idempotency/InvalidIdempotencyKeyException.java`
- Modify: `idempotency/IdempotencyPayloadCodec.java` — add `canonicalise`
- Modify: `idempotency/IdempotencyService.java` — reject an unusable key
- Test: `backend/src/test/java/com/walletledger/money/MoneyEndpointsIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MoneyEndpointsIT extends AbstractIntegrationTest {

    private String key() {
        return UUID.randomUUID().toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deposit(
            String token, String idemKey, String body) {
        return post("/api/v1/wallet/deposits")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", idemKey)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    @Test
    void aDepositReturnsTheNewBalance() throws Exception {
        String token = accessTokenFor("dep-" + UUID.randomUUID());

        mockMvc.perform(deposit(token, key(), """
                        {"amount":"120.5000","description":"salary"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value("120.5000"))
                .andExpect(jsonPath("$.balanceAfter").value("120.5000"));
    }

    @Test
    void sendingTheSameKeyTwiceChargesOnce() throws Exception {
        String token = accessTokenFor("dup-" + UUID.randomUUID());
        String idemKey = key();
        String body = """
                {"amount":"10.0000","description":"double click"}""";

        mockMvc.perform(deposit(token, idemKey, body)).andExpect(status().isCreated());
        mockMvc.perform(deposit(token, idemKey, body)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.balance").value("10.0000"));
    }

    @Test
    void aMissingIdempotencyKeyIsRejected() throws Exception {
        String token = accessTokenFor("nokey-" + UUID.randomUUID());

        mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"1.0000"}"""))
                .andExpect(status().isBadRequest());
    }

    /** Five decimal places must be refused, never silently rounded. */
    @Test
    void anAmountFinerThanFourDecimalPlacesIsRejected() throws Exception {
        String token = accessTokenFor("scale-" + UUID.randomUUID());

        mockMvc.perform(deposit(token, key(), """
                        {"amount":"1.00001"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroOrNegativeAmountIsRejected() throws Exception {
        String token = accessTokenFor("sign-" + UUID.randomUUID());

        mockMvc.perform(deposit(token, key(), """
                        {"amount":"0"}""")).andExpect(status().isBadRequest());
        mockMvc.perform(deposit(token, key(), """
                        {"amount":"-5.0000"}""")).andExpect(status().isBadRequest());
    }

    @Test
    void withdrawingMoreThanTheBalanceIsAConflict() throws Exception {
        String token = accessTokenFor("over-" + UUID.randomUUID());
        mockMvc.perform(deposit(token, key(), """
                {"amount":"5.0000"}""")).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallet/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"5.0001"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }

    @Test
    void aTransferMovesMoneyAndIsVisibleToBothParties() throws Exception {
        String payerName = "payer-" + UUID.randomUUID();
        String payeeName = "payee-" + UUID.randomUUID();
        String payerToken = accessTokenFor(payerName);
        String payeeToken = accessTokenFor(payeeName);

        mockMvc.perform(deposit(payerToken, key(), """
                {"amount":"60.0000"}""")).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(payerToken))
                        .header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"toUsername":"%s","amount":"25.0000","description":"lunch"}""".formatted(payeeName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value("35.0000"));

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(payeeToken)))
                .andExpect(jsonPath("$.balance").value("25.0000"));
    }

    @Test
    void anUnauthenticatedMoneyRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/deposits")
                        .header("Idempotency-Key", key())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount":"1.0000"}"""))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=MoneyEndpointsIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL — every endpoint returns 404.

- [ ] **Step 3: Write the request DTOs**

`@Digits(integer = 15, fraction = 4)` mirrors `NUMERIC(19,4)` exactly and **rejects** a finer
amount rather than rounding it. `@DecimalMin("0.0001")` makes zero and negatives impossible, so
"withdraw a negative amount" can never become a disguised deposit.

`money/AmountRequest.java`:

```java
package com.walletledger.money;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull
        @DecimalMin(value = "0.0001", message = "amount must be positive")
        @Digits(integer = 15, fraction = 4, message = "amount supports at most 4 decimal places")
        BigDecimal amount,

        @Size(max = 255)
        String description) {
}
```

`money/TransferRequest.java`:

```java
package com.walletledger.money;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank
        @Size(max = 50)
        String toUsername,

        @NotNull
        @DecimalMin(value = "0.0001", message = "amount must be positive")
        @Digits(integer = 15, fraction = 4, message = "amount supports at most 4 decimal places")
        BigDecimal amount,

        @Size(max = 255)
        String description) {
}
```

- [ ] **Step 4: Add `InvalidIdempotencyKeyException`**

Without this the header is only bounded by the column width, and an over-long key would surface as
a database error on a money endpoint.

```java
package com.walletledger.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InvalidIdempotencyKeyException extends ErrorResponseException {

    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Invalid Idempotency-Key");
        detail.setDetail("Idempotency-Key must be between 8 and 64 characters");
        return detail;
    }
}
```

- [ ] **Step 5: Extend the codec and guard the key**

Add to `IdempotencyPayloadCodec`:

```java
    /** Records serialise their components in declaration order, so this is stable. */
    public String canonicalise(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not canonicalise a request", e);
        }
    }
```

Add at the top of `IdempotencyService.execute`:

```java
        if (key == null || key.length() < 8 || key.length() > 64) {
            throw new InvalidIdempotencyKeyException();
        }
```

- [ ] **Step 6: Write `MoneyController`**

```java
package com.walletledger.money;

import com.walletledger.auth.AuthenticatedUser;
import com.walletledger.idempotency.IdempotencyPayloadCodec;
import com.walletledger.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MoneyController {

    private static final String DEPOSITS = "POST /api/v1/wallet/deposits";
    private static final String WITHDRAWALS = "POST /api/v1/wallet/withdrawals";
    private static final String TRANSFERS = "POST /api/v1/transfers";

    private final MoneyService money;
    private final IdempotencyService idempotency;
    private final IdempotencyPayloadCodec codec;

    public MoneyController(MoneyService money, IdempotencyService idempotency,
                           IdempotencyPayloadCodec codec) {
        this.money = money;
        this.idempotency = idempotency;
        this.codec = codec;
    }

    @PostMapping("/wallet/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView deposit(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody AmountRequest request) {
        return idempotency.execute(user.id(), key, DEPOSITS, codec.canonicalise(request),
                () -> money.deposit(user.id(), request.amount(), request.description()));
    }

    @PostMapping("/wallet/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView withdraw(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody AmountRequest request) {
        return idempotency.execute(user.id(), key, WITHDRAWALS, codec.canonicalise(request),
                () -> money.withdraw(user.id(), request.amount(), request.description()));
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionView transfer(@AuthenticationPrincipal AuthenticatedUser user,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody TransferRequest request) {
        return idempotency.execute(user.id(), key, TRANSFERS, codec.canonicalise(request),
                () -> money.transfer(user.id(), request.toUsername(), request.amount(),
                        request.description()));
    }
}
```

A missing `Idempotency-Key` header is a 400 from Spring's own binding, so no extra code is needed
for that case.

- [ ] **Step 7: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=MoneyEndpointsIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 8, Failures: 0`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/money/MoneyEndpointsIT.java
```

```bash
git commit -m "feat: expose deposit, withdrawal and transfer over HTTP" -m "Every money-writing endpoint requires an Idempotency-Key header, so a double click or a timeout retry cannot charge twice. Amounts are validated with @Digits(integer = 15, fraction = 4), mirroring NUMERIC(19,4) exactly: a finer amount is refused rather than silently rounded, and @DecimalMin stops a negative withdrawal becoming a disguised deposit."
```

---

## Task 7: Audit that survives a rollback

**Files:**
- Create: `audit/AuditOutcome.java`, `audit/AuditLogger.java`
- Modify: `money/MoneyService.java` — record success and failure
- Test: `backend/src/test/java/com/walletledger/audit/AuditLoggerIT.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=AuditLoggerIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL — no audit rows exist.

- [ ] **Step 3: Write `AuditOutcome` and `AuditLogger`**

`audit/AuditOutcome.java`:

```java
package com.walletledger.audit;

public enum AuditOutcome {
    SUCCESS,
    FAILURE
}
```

`audit/AuditLogger.java` — its own bean, for the same proxy reason as `RefreshTokenRevoker`:

```java
package com.walletledger.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW, in its own bean. The caller's transaction is suspended and this row commits on
 * its own, so a refused operation still leaves a trail after the caller rolls back. Calling a
 * REQUIRES_NEW method on {@code this} would bypass the Spring proxy and silently join the
 * caller's transaction — the exact bug this design exists to avoid.
 * <p>
 * JdbcTemplate rather than an entity: this writes and is never read by the application, so an
 * entity would buy nothing and would join the caller's persistence context.
 */
@Component
public class AuditLogger {

    private final JdbcTemplate jdbc;

    public AuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String action, String detail, AuditOutcome outcome) {
        jdbc.update("insert into audit_log (user_id, action, detail, outcome) values (?, ?, ?, ?)",
                userId, action, detail, outcome.name());
    }
}
```

- [ ] **Step 4: Record outcomes in `MoneyService`**

First replace the field list and constructor so the logger is injected:

```java
    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final LedgerPostingService posting;
    private final AuditLogger audit;

    public MoneyService(AppUserRepository users, AccountRepository accounts,
                        LedgerPostingService posting, AuditLogger audit) {
        this.users = users;
        this.accounts = accounts;
        this.posting = posting;
        this.audit = audit;
    }
```

Add the imports `com.walletledger.audit.AuditLogger` and `com.walletledger.audit.AuditOutcome`.

Then wrap each of the three methods. Deposit is shown; withdraw and transfer take the same shape
with their own action names (`WITHDRAWAL`, `TRANSFER`).

```java
    @Transactional
    public TransactionView deposit(long userId, BigDecimal amount, String description) {
        try {
            long walletId = walletIdOf(userId);
            LedgerTransaction tx = posting.post(TransactionType.DEPOSIT, userId, description,
                    SYSTEM_FUNDING, walletId, amount);
            TransactionView view = TransactionView.of(tx, amount, balanceAfter(walletId));
            audit.record(userId, "DEPOSIT", "amount=" + amount, AuditOutcome.SUCCESS);
            return view;
        } catch (RuntimeException e) {
            audit.record(userId, "DEPOSIT", "amount=" + amount + " rejected: "
                    + e.getClass().getSimpleName(), AuditOutcome.FAILURE);
            throw e;
        }
    }
```

The detail records the exception type, not its message: messages can carry balances, and an audit
table is read by more people than the account holder.

- [ ] **Step 5: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=AuditLoggerIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 2, Failures: 0`.

If `aRefusedOperationStillLeavesATrail` finds zero rows, `AuditLogger` was called through `this`
somewhere, or is not a separate bean — the proxy was bypassed and the insert rolled back with the
caller.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/audit
```

```bash
git commit -m "feat: record every money operation in an audit log that survives rollback" -m "AuditLogger is its own bean with REQUIRES_NEW, so the row commits independently of the caller. A refused operation therefore still leaves a trail, which is the only case where an audit log earns its keep. Calling a REQUIRES_NEW method on this would bypass the Spring proxy and silently join the caller's transaction, so the separate bean is load-bearing, not style. The detail column records the exception type rather than its message, because messages can carry balances and audit rows are read by more people than the account holder."
```

---

## Task 8: One hundred threads on one wallet

**Files:**
- Modify: `backend/src/test/java/com/walletledger/AbstractIntegrationTest.java` — enlarge the pool
- Test: `backend/src/test/java/com/walletledger/money/ConcurrentWithdrawalIT.java`

- [ ] **Step 1: Enlarge the connection pool for tests**

HikariCP defaults to ten connections. With the default, 150 threads queue through ten and the test
proves correctness without ever creating much contention. Add to `testProperties`:

```java
        // Real contention needs real connections. PostgreSQL's default max_connections is 100.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
```

- [ ] **Step 2: Write the test**

```java
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentWithdrawalIT extends AbstractIntegrationTest {

    private static final BigDecimal ONE = new BigDecimal("1.0000");

    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 150 threads each try to withdraw 1 from a wallet holding 100. Exactly 100 must succeed,
     * 50 must be refused, and the balance must land on exactly zero — never below it, and never
     * above it either, which is what a lost update would produce.
     */
    @Test
    void moreThreadsThanMoneyStillLeavesTheBalanceExact() throws Exception {
        AppUser user = users.save(AppUser.create("race-" + UUID.randomUUID(), "hash"));
        Account wallet = accounts.save(Account.walletFor(user.getId()));
        money.deposit(user.getId(), new BigDecimal("100.0000"), "seed");

        int attempts = 150;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>(attempts);

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    money.withdraw(user.getId(), ONE, "concurrent");
                    return Boolean.TRUE;
                } catch (InsufficientFundsException e) {
                    return Boolean.FALSE;
                }
            }));
        }
        startGate.countDown();

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            // Any other exception surfaces here as an ExecutionException and fails the test,
            // which is the point: only InsufficientFunds is an acceptable refusal.
            if (result.get(120, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded).isEqualTo(100);

        BigDecimal balance = jdbc.queryForObject(
                "select balance from account where id = ?", BigDecimal.class, wallet.getId());
        assertThat(balance).isEqualByComparingTo("0.0000");

        BigDecimal derived = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry where account_id = ?",
                BigDecimal.class, wallet.getId());
        assertThat(derived).isEqualByComparingTo("0.0000");

        assertThat(jdbc.queryForObject(
                "select count(*) from ledger_entry where account_id = ? and amount < 0",
                Integer.class, wallet.getId())).isEqualTo(100);
    }
}
```

- [ ] **Step 3: Run it**

Run: `mvnd -B verify "-Dit.test=ConcurrentWithdrawalIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 1, Failures: 0`. Record the wall time — Phase 1C compares it against the
optimistic and serializable strategies on this same test.

This test is expected to pass first time. That is not a reason to skip running it: without a
measured pass it is a claim, and Phase 1C needs the number.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/walletledger
```

```bash
git commit -m "test: prove 150 concurrent withdrawals leave an exact balance" -m "150 threads each withdraw 1 from a wallet holding 100. Exactly 100 succeed, 50 are refused, and the balance lands on exactly zero: never below, which the CHECK constraint would catch, and never above, which is what a lost update produces. Only InsufficientFundsException counts as an acceptable refusal; anything else surfaces as an ExecutionException and fails the test. The test connection pool is raised to 32 because Hikari's default of ten would queue the threads and quietly remove the contention being measured."
```

---

## Task 9: Opposite transfers must not deadlock

**Files:**
- Test: `backend/src/test/java/com/walletledger/money/TransferDeadlockIT.java`

- [ ] **Step 1: Write the test**

```java
package com.walletledger.money;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import com.walletledger.auth.AppUser;
import com.walletledger.auth.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransferDeadlockIT extends AbstractIntegrationTest {

    private static final BigDecimal ONE = new BigDecimal("1.0000");

    @Autowired private MoneyService money;
    @Autowired private AppUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;

    private AppUser seeded(String prefix, String amount) {
        AppUser user = users.save(AppUser.create(prefix + UUID.randomUUID(), "hash"));
        accounts.save(Account.walletFor(user.getId()));
        money.deposit(user.getId(), new BigDecimal(amount), "seed");
        return user;
    }

    private BigDecimal balanceOf(AppUser user) {
        return accounts.findByOwnerUserId(user.getId()).orElseThrow().getBalance();
    }

    /**
     * The classic deadlock shape: A→B and B→A at the same time. Without a global lock ordering
     * each transaction holds one row and waits for the other, and PostgreSQL breaks the cycle by
     * aborting one with SQLSTATE 40P01 after deadlock_timeout. LedgerPostingService locks in
     * ascending account id order, so the cycle cannot form and no transfer is ever aborted.
     */
    @Test
    void simultaneousOppositeTransfersNeitherDeadlockNorLoseMoney() throws Exception {
        AppUser alice = seeded("alice-", "200.0000");
        AppUser bob = seeded("bob-", "200.0000");
        BigDecimal totalBefore = balanceOf(alice).add(balanceOf(bob));

        int eachWay = 50;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>(eachWay * 2);

        for (int i = 0; i < eachWay; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                money.transfer(alice.getId(), bob.getUsername(), ONE, "a to b");
                return null;
            }));
            results.add(pool.submit(() -> {
                startGate.await();
                money.transfer(bob.getId(), alice.getUsername(), ONE, "b to a");
                return null;
            }));
        }
        startGate.countDown();

        // Any deadlock arrives here as an ExecutionException wrapping CannotAcquireLockException.
        for (Future<Void> result : results) {
            result.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(balanceOf(alice).add(balanceOf(bob))).isEqualByComparingTo(totalBefore);
        assertThat(jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry", BigDecimal.class))
                .isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 2: Run it**

Run: `mvnd -B verify "-Dit.test=TransferDeadlockIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 1, Failures: 0`, and **no** `deadlock detected` anywhere in the output.

Grep the log for `40P01` explicitly. A passing test only shows no exception escaped; seeing the
absence of the SQLSTATE is the stronger statement. The control experiment — removing the ordering
and watching the deadlock appear — belongs to Phase 1C, where it is a teaching artefact rather
than a risk to the build.

- [ ] **Step 3: Run the whole suite**

Run: `mvnd -B verify`

Expected: every test green, none skipped.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/walletledger/money/TransferDeadlockIT.java
```

```bash
git commit -m "test: prove opposite transfers do not deadlock" -m "Fifty threads transfer A to B while fifty transfer B to A. Without a global lock ordering each transaction would hold one row and wait for the other, and PostgreSQL would abort one with SQLSTATE 40P01 after deadlock_timeout. Locking in ascending account id order means the cycle cannot form. The test asserts no transfer was aborted, that the two balances still sum to what they started with, and that the ledger still sums to zero."
```

---

## Definition of done for Phase 1B

- [ ] `mvnd -B verify` green, no skipped tests
- [ ] 150 concurrent withdrawals from a wallet holding 100 leave exactly `0.0000`, with exactly 100 successes
- [ ] Fifty A→B and fifty B→A transfers complete with no `40P01` in the log
- [ ] The same `Idempotency-Key` sent twice produces one ledger entry pair and replays the first response
- [ ] The same key with a different body is refused with 422
- [ ] A refused withdrawal leaves an audit row and no ledger entries
- [ ] `select sum(amount) from ledger_entry` returns `0` after the whole suite
- [ ] `SELECT ... FOR UPDATE` was observed in the SQL log, not merely assumed (Task 3, Step 7)
- [ ] No container belonging to another project was stopped

## Notes for the implementer

- **Do not run git.** Hand the owner the one-line commit commands exactly as written.
- **Do not weaken a database constraint or a test assertion to make something pass.**
- **Record every real bug** in `docs/hoc/NHAT_KY_BUG.md`, following the existing five entries:
  symptom, first wrong hypothesis, why it was wrong, the correct fix, the lesson.
- **Measure, do not assert.** Where this plan says to record a number — lock statement, wall time,
  success count — record the observed one. Phase 1C compares against it.
