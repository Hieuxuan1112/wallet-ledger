# Phase 1C — Locking Strategies, Isolation, and Quality Gates

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn "we chose pessimistic locking" from an assertion into a **measurement**. Four implementations of the same interface — pessimistic, optimistic, serializable, and one deliberately unsafe — run the **same** concurrency tests, producing a table of real numbers. The unsafe one must **fail**, and its failure is the proof that the other three are doing something. Then lock the codebase down with ArchUnit rules and a JaCoCo coverage gate.

**Architecture:** `LedgerPostingService.post` currently does two jobs: it decides *how* to acquire the accounts safely, and it performs the *posting* (adjust balances, write the entry pair). Phase 1C extracts the first job behind a `BalanceMutator` interface with four implementations, leaving the posting logic in one place. The production bean stays pessimistic; the others are selected per-test.

**Tech Stack:** Unchanged — Java 21, Spring Boot 3.5.16, PostgreSQL 16, JUnit 5, Testcontainers 1.21.4. ArchUnit 1.5.0 is already a dependency with no rules written. JaCoCo is the only new plugin.

**Starting point:** Phase 1B complete — 80 tests green, 0 skipped, migrations V1–V4, all nine code-review findings fixed. `Account` has a `@Version` column that nothing uses yet; that column exists precisely for this phase.

**Scope boundary:** Statement, refund, outbox and Kafka are Phase 2. Frontend, CI and deployment are Phase 3. This phase adds **no new features** — it only measures and constrains what Phase 1B built.

---

## Environment

Unchanged. Paste once per PowerShell session:

```powershell
function mvnd { docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" -v "//var/run/docker.sock:/var/run/docker.sock" -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true --add-host host.docker.internal:host-gateway -w /app maven:3.9-eclipse-temurin-21 mvn @args }
```

Every `-D` argument must be quoted — PowerShell splits `-Dit.test=Foo` at the dot. See bug #2.

Do not use `Select-Object -First N` on a docker run pipeline: it closes the pipe, PowerShell kills docker, and you get a **fake exit 255**. Write the log to a file and filter the file. See bug #6.

**Git:** the repository owner runs every `git` write command themselves, in **cmd.exe**. Commit commands are therefore written as a single line with repeated `-m` flags, and never carry a `Co-Authored-By` trailer.

---

## Baseline measurements to beat (already recorded)

Taken from `<testcase time=...>` in `target/failsafe-reports/TEST-*.xml`, **not** from Maven's `Time elapsed` line, which includes ~100 s of Spring context startup:

| Test | Pessimistic (current) |
|---|---|
| `ConcurrentWithdrawalIT` — 150 threads withdraw 1 from a wallet holding 100 | **11.974 s** |
| `TransferDeadlockIT` — 50 A→B + 50 B→A | **13.019 s** |

**Measured again during Task 1, same code, same pessimistic strategy, with the JaCoCo agent
attached (which should make it slower, not faster):**

| Test | Baseline above | Task 1 re-run |
|---|---|---|
| `ConcurrentWithdrawalIT` | 11.974 s | **6.396 s** |
| `TransferDeadlockIT` | 13.019 s | **3.131 s** |

Two to four times faster with no change to the locking. **Run-to-run noise on this machine is
larger than any difference the four strategies are likely to show.** Task 7 must therefore not
compare four separate Maven runs — that table would be measuring background load, not locking.
Run all four strategies inside one `mvnd -B verify`, and repeat the run at least twice, reporting
the spread rather than a single figure.

---

## File structure

| File | Responsibility |
|---|---|
| `ledger/BalanceMutator.java` | The interface: acquire two accounts safely, hand them back |
| `ledger/PessimisticBalanceMutator.java` | `SELECT ... FOR UPDATE`, ascending id order — the production bean |
| `ledger/OptimisticBalanceMutator.java` | `@Version`, retry on `OptimisticLockingFailureException` |
| `ledger/SerializableBalanceMutator.java` | `Isolation.SERIALIZABLE`, retry on `40001` |
| `test/.../ledger/UnsafeBalanceMutator.java` | **Test sources only.** No lock at all — must lose money |
| `test/.../ledger/AbstractConcurrencyContract.java` | The shared test body all four strategies run |
| `test/.../arch/ArchitectureRulesTest.java` | Three ArchUnit rules |
| `pom.xml` | JaCoCo plugin + coverage gate |

**Why `UnsafeBalanceMutator` lives in test sources:** a class whose only purpose is to lose money must not be reachable from production code, ever. Putting it under `src/test/java` makes that structural rather than a matter of discipline.

---

## Task 1: JaCoCo and the coverage gate

**Files:**
- Modify: `backend/pom.xml`

- [x] **Step 1: Add the plugin**

Add to `<properties>`:

```xml
    <jacoco.version>0.8.13</jacoco.version>
```

Add to `<build><plugins>`:

```xml
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>${jacoco.version}</version>
        <executions>
          <execution>
            <id>prepare-agent</id>
            <goals><goal>prepare-agent</goal></goals>
          </execution>
          <!-- Almost every test here is an integration test, so the failsafe run is where
               the coverage actually comes from. A surefire-only report would read ~0%. -->
          <execution>
            <id>prepare-agent-integration</id>
            <goals><goal>prepare-agent-integration</goal></goals>
          </execution>
          <execution>
            <id>merge-results</id>
            <phase>verify</phase>
            <goals><goal>merge</goal></goals>
            <configuration>
              <fileSets>
                <fileSet>
                  <directory>${project.build.directory}</directory>
                  <includes><include>*.exec</include></includes>
                </fileSet>
              </fileSets>
              <destFile>${project.build.directory}/jacoco-merged.exec</destFile>
            </configuration>
          </execution>
          <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
            <configuration>
              <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
            </configuration>
          </execution>
          <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
              <dataFile>${project.build.directory}/jacoco-merged.exec</dataFile>
              <rules>
                <rule>
                  <element>BUNDLE</element>
                  <limits>
                    <limit>
                      <counter>INSTRUCTION</counter>
                      <value>COVEREDRATIO</value>
                      <minimum>0.85</minimum>
                    </limit>
                    <limit>
                      <counter>BRANCH</counter>
                      <value>COVEREDRATIO</value>
                      <minimum>0.75</minimum>
                    </limit>
                  </limits>
                </rule>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

- [x] **Step 2: Measure before deciding anything**

Run: `mvnd -B verify`

**Do not adjust the thresholds before seeing the number.** Read the real figure from
`target/site/jacoco/index.html` (or the `check` failure message, which prints it).

Record it here, in this plan file, as a fact:

```
Measured coverage, first run: 92.36% instruction, 83.33% branch
  instructions 1800 covered / 149 missed (1949 total)
  branches       45 covered /   9 missed (54 total)
  Read from target/site/jacoco/jacoco.csv after `mvnd -B verify`, 80 tests green.
  Both gates pass untouched. Caveat worth knowing: the branch denominator is only 54,
  so a single uncovered branch moves the figure by ~1.9 points. The branch gate is
  therefore far more brittle than the instruction gate on a codebase this size.
```

- [x] **Step 3: React to the number honestly**

| If the number is | Do this |
|---|---|
| ≥ 85 / 75 | Nothing. The gate holds. |
| Slightly below | Write the missing tests. Do **not** lower the gate. |
| Far below (< 70) | Stop and report to the owner with the class-by-class breakdown before touching either the gate or the tests. |

**Never lower a gate to make a build pass.** If the gate is wrong, say so and explain why in the commit message; do not quietly move it.

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml docs/superpowers/plans/2026-09-08-phase1c-locking-strategies-and-gates.md
```

```bash
git commit -m "build: add JaCoCo with an 85 percent instruction and 75 percent branch gate" -m "Coverage is merged from both the surefire and failsafe executions. Almost every test in this project is an integration test, so a surefire-only report would read close to zero and the gate would be meaningless."
```

---

## Task 2: ArchUnit rules

ArchUnit is already a dependency with no rules written. Three rules, each protecting a decision Phase 1B actually made.

**Files:**
- Create: `backend/src/test/java/com/walletledger/arch/ArchitectureRulesTest.java`

- [ ] **Step 1: Write the rules**

```java
package com.walletledger.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rules that encode decisions this project has already paid for. Each one would have caught a
 * real defect, or protects an invariant explained in docs/hoc/KIEN_TRUC_VA_QUYET_DINH.md.
 */
class ArchitectureRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.walletledger");
    }

    /**
     * The chokepoint. If anything but the ledger package can change a balance, the guarantee
     * that all money moves through one place is gone — and with it the value of every check
     * inside post().
     */
    @Test
    void onlyTheLedgerMayMutateBalances() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.walletledger.ledger..")
                .should().callMethod(com.walletledger.account.Account.class, "credit", java.math.BigDecimal.class)
                .orShould().callMethod(com.walletledger.account.Account.class, "debit", java.math.BigDecimal.class)
                .because("all money movement must go through LedgerPostingService");

        rule.check(production);
    }

    /**
     * Controllers own HTTP, services own rules. A controller reaching a repository directly is
     * how business logic starts leaking into the web layer.
     */
    @Test
    void controllersDoNotTouchRepositories() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .because("controllers translate HTTP; services own the rules");

        rule.check(production);
    }

    /**
     * Bug 10: a negative amount reversed the direction of a posting, and every HTTP test stayed
     * green because the DTOs rejected it at the boundary. This rule stops the money path from
     * ever depending on that boundary again.
     */
    @Test
    void theLedgerDoesNotDependOnTheWebLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.walletledger.ledger..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "jakarta.validation..")
                .because("the posting primitive must be correct without HTTP validation above it");

        rule.check(production);
    }
}
```

- [ ] **Step 2: Run and read the failures carefully**

Run: `mvnd -B verify "-Dit.test=ArchitectureRulesTest" "-DfailIfNoSpecifiedTests=false"`

**Expect at least one failure.** Rule 3 will likely fail: `InsufficientFundsException` lives in `ledger/` and extends `ErrorResponseException` from `org.springframework.web`.

**This is a real finding, not a rule to weaken.** The exception is an HTTP concern living in the money package. Two honest options:

| Option | Trade-off |
|---|---|
| Move `InsufficientFundsException` to `money/` | Clean layering; `ledger` becomes HTTP-free. `LedgerPostingService` then throws a plain domain exception that `MoneyService` translates |
| Narrow the rule to exclude `..Exception` classes | Faster, but admits HTTP into the ledger package permanently |

Take the first. Report the choice in the commit message so it is not mistaken for an accident.

- [ ] **Step 3: Make the rules pass by fixing the code, not the rules**

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/arch
```

```bash
git commit -m "test: add ArchUnit rules protecting the money-path invariants" -m "Three rules: only the ledger package may call Account.credit or Account.debit, controllers may not depend on repositories, and the ledger package may not depend on Spring Web or Bean Validation. The third rule failed on the first run and the fix was to move the exception rather than to weaken the rule, because bug 10 was caused by exactly that dependency direction: the posting primitive trusting validation that lived above it."
```

---

## Task 3: Extract the `BalanceMutator` interface

No behaviour change. This is the refactor that makes the comparison possible.

**Files:**
- Create: `ledger/BalanceMutator.java`, `ledger/PessimisticBalanceMutator.java`
- Modify: `ledger/LedgerPostingService.java`

- [ ] **Step 1: Define the interface**

```java
package com.walletledger.ledger;

import com.walletledger.account.Account;

/**
 * How the two accounts in a posting are acquired safely. The posting itself — adjusting the
 * balances and writing the entry pair — is identical for every strategy and stays in
 * LedgerPostingService. Only the acquisition differs, which is exactly what Phase 1C measures.
 */
public interface BalanceMutator {

    /** Called with ids in ascending order. Implementations must not reorder them. */
    AccountPair acquire(long firstId, long secondId);

    /** What the strategy is called in the measurement table. */
    String strategyName();

    record AccountPair(Account first, Account second) {
    }
}
```

- [ ] **Step 2: Move the current locking into `PessimisticBalanceMutator`**

```java
package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The production strategy. Two separate SELECT ... FOR UPDATE statements, lower id first.
 * A single "where id in (a, b) order by id for update" would NOT do: PostgreSQL makes no promise
 * about the order in which it acquires row locks within one statement.
 */
@Component
@Primary
public class PessimisticBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public PessimisticBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(lock(firstId), lock(secondId));
    }

    @Override
    public String strategyName() {
        return "pessimistic";
    }

    private Account lock(long accountId) {
        return accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
```

- [ ] **Step 3: Make `LedgerPostingService` depend on the interface**

Replace the `lock(...)` private method and the two `lock` calls with:

```java
    BalanceMutator.AccountPair pair = mutator.acquire(firstId, secondId);
    Account first = pair.first();
    Account second = pair.second();
```

Keep everything else — the sign check, the self-payment check, the ordering computation, the entry writes — exactly as it is. The ordering stays in `LedgerPostingService` deliberately: it is a property of the *posting*, not of the strategy, and every strategy must obey it.

- [ ] **Step 4: Run the whole suite**

Run: `mvnd -B verify`

Expected: **80 tests, 0 failures.** A pure refactor that changes a test count has changed behaviour.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/walletledger/ledger
```

```bash
git commit -m "refactor: extract BalanceMutator so locking strategies can be compared" -m "No behaviour change: the same 80 tests pass. Acquisition of the two accounts moves behind an interface; the posting itself and the ascending-id ordering stay in LedgerPostingService, because the ordering is a property of the posting rather than of any one strategy and every implementation has to obey it."
```

---

## Task 4: The deliberately unsafe strategy, and the lost update it produces

This is the teaching artefact of the whole phase. Everything else proves a strategy works; this one shows what "works" was preventing.

**Files:**
- Create: `backend/src/test/java/com/walletledger/ledger/UnsafeBalanceMutator.java`
- Create: `backend/src/test/java/com/walletledger/ledger/LostUpdateIT.java`

- [ ] **Step 1: Write the unsafe strategy — in test sources only**

```java
package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;

/**
 * Deliberately broken, and deliberately unreachable from production code — it lives under
 * src/test/java so no wiring accident can select it in a running application.
 * <p>
 * It reads both accounts with no lock of any kind. Two transactions can therefore read the same
 * balance, both decide they can afford the withdrawal, and both write: a lost update.
 */
public class UnsafeBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public UnsafeBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "unsafe";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
```

- [ ] **Step 2: Write the test that must FAIL to prove the point**

The assertion is inverted from every other concurrency test in this repository: it asserts that money **is** lost.

```java
package com.walletledger.ledger;

// imports as in ConcurrentWithdrawalIT, plus:
// import org.springframework.test.context.bean.override.mockito.MockitoBean; -- NOT used here
// import org.springframework.boot.test.context.TestConfiguration;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Import;

/**
 * The control experiment. Same wallet, same threads, same money — only the strategy differs.
 * Without a lock, the balance does NOT land on zero, and the ledger does not match the cached
 * balance. This test asserts the damage, so if it ever starts passing "cleanly" that means the
 * experiment stopped reproducing and the comparison in Task 7 is no longer honest.
 */
class LostUpdateIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class UnsafeStrategy {
        @Bean
        @Primary
        BalanceMutator unsafeMutator(AccountRepository accounts) {
            return new UnsafeBalanceMutator(accounts);
        }
    }

    // ... same setup as ConcurrentWithdrawalIT: wallet with 100, 150 threads each withdrawing 1

    @Test
    void withoutALockMoneyIsActuallyLost() throws Exception {
        // ... run the 150 threads ...

        BigDecimal cached = jdbc.queryForObject(
                "select balance from account where id = ?", BigDecimal.class, walletId);
        BigDecimal derived = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from ledger_entry where account_id = ?",
                BigDecimal.class, walletId);

        // At least one of these must show the damage. Record which one, and by how much.
        System.out.printf("UNSAFE: successes=%d cached=%s derived=%s%n", succeeded, cached, derived);

        assertThat(succeeded).isGreaterThan(100);   // more withdrawals succeeded than there was money
    }
}
```

- [ ] **Step 3: Run it and record what actually happens**

Run: `mvnd -B verify "-Dit.test=LostUpdateIT" "-DfailIfNoSpecifiedTests=false"`

**This step is an experiment, not a verification.** Write down the observed numbers here:

```
Observed with UnsafeBalanceMutator, 150 threads, wallet holding 100:
  successes = ____   (pessimistic gives exactly 100)
  cached balance = ____
  derived from ledger = ____
  ck_wallet_non_negative violations = ____
```

**If it does not reproduce**, do not force it. Report the outcome honestly and investigate why — likely candidates: the CHECK constraint catching it first and turning the lost update into a visible error, or the threads not actually overlapping. Both are interesting results and belong in `docs/hoc/NHAT_KY_BUG.md`. A control experiment that quietly fails to reproduce and gets deleted is worse than one that reproduces something unexpected.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/walletledger/ledger
```

```bash
git commit -m "test: reproduce a lost update with a deliberately unlocked strategy" -m "The control experiment for the whole phase. Same wallet, same 150 threads, same money; only the acquisition strategy differs. It lives in test sources so no wiring accident can select it in production. The assertion is inverted on purpose: it asserts that money IS lost, so if it ever stops reproducing, the comparison against the safe strategies has stopped meaning anything."
```

---

## Task 5: The optimistic strategy

**Files:**
- Create: `ledger/OptimisticBalanceMutator.java`
- Test: reuse the shared contract from Task 7

- [ ] **Step 1: Write it**

```java
package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.stereotype.Component;

/**
 * No database lock. Account carries a @Version column, so Hibernate appends
 * "and version = ?" to the UPDATE and throws if another transaction got there first.
 * <p>
 * The retry has to happen ABOVE the transaction, not inside it: once a transaction has failed
 * its version check it is finished, and retrying inside it retries nothing. That is why this
 * class only acquires, and the retry lives in the caller — see LedgerPostingService's
 * @Retryable wrapper, or the explicit loop in the test contract.
 */
@Component
public class OptimisticBalanceMutator implements BalanceMutator {

    private final AccountRepository accounts;

    public OptimisticBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "optimistic";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
```

- [ ] **Step 2: Confirm the version check actually fires**

The read path looks identical to `UnsafeBalanceMutator`. **The difference is entirely in what Hibernate does at flush time**, and that difference must be observed, not assumed — this is the same trap as bug #7.

Run the suite with SQL logging and confirm the UPDATE carries a version predicate:

```powershell
mvnd -B verify "-Dit.test=..." "-DfailIfNoSpecifiedTests=false" "-Dlogging.level.org.hibernate.SQL=DEBUG"
```

Expected shape:

```sql
update account set balance=?, version=? where id=? and version=?
```

**If the `and version=?` is absent, the optimistic strategy is not optimistic** — it is the unsafe one with a different name, and every number measured from it would be a lie. Record the observed SQL in the commit message.

- [ ] **Step 3: Add the retry, above the transaction boundary**

Retry belongs in a wrapper bean, because a transaction that failed its version check cannot be retried from inside itself.

- [ ] **Step 4: Commit**

---

## Task 6: The serializable strategy

**Files:**
- Create: `ledger/SerializableBalanceMutator.java`

- [ ] **Step 1: Write it**

The acquisition is a plain read; the isolation level does the work, so it is declared on the transactional method:

```java
@Transactional(isolation = Isolation.SERIALIZABLE)
```

PostgreSQL implements this as **Serializable Snapshot Isolation**: it does not lock, it detects dangerous read-write patterns at commit and aborts one transaction with **SQLSTATE 40001** (`serialization_failure`).

- [ ] **Step 2: Retry on 40001, and count the retries**

Retry is **mandatory** here, not optional — the PostgreSQL documentation says applications using SERIALIZABLE must be prepared to retry.

Count the retries and report the number. It is the most interesting figure in the whole comparison:

```
serializable: 150 attempts → ____ serialization failures → ____ retries
```

- [ ] **Step 3: Confirm 40001 really appears**

Grep the log for `40001`. If it never appears under 150 concurrent withdrawals, the isolation level is probably not being applied — check that the annotation is on the outermost transactional method, since `REQUIRED` propagation means an inner annotation is ignored.

- [ ] **Step 4: Commit**

---

## Task 7: One contract, four strategies, one table

**Files:**
- Create: `backend/src/test/java/com/walletledger/ledger/AbstractConcurrencyContract.java`
- Create: four subclasses, one per strategy

- [ ] **Step 1: Extract the shared test body**

The 150-thread withdrawal and the opposite-transfer test move into an abstract class. Each strategy gets a subclass that supplies the bean and nothing else.

```java
abstract class AbstractConcurrencyContract extends AbstractIntegrationTest {

    protected abstract String expectedStrategy();

    /** Strategies that must keep the invariant. UnsafeBalanceMutator overrides this to false. */
    protected boolean mustBeCorrect() {
        return true;
    }

    @Test
    void moreThreadsThanMoneyStillLeavesTheBalanceExact() throws Exception { ... }

    @Test
    void simultaneousOppositeTransfersNeitherDeadlockNorLoseMoney() throws Exception { ... }
}
```

**Why one shared body matters:** if each strategy had its own test, a difference in the numbers could come from a difference in the tests. Running the same body is what makes the comparison mean anything.

- [ ] **Step 2: Run all four and collect the numbers from the XML**

Read `<testcase time=...>` from `target/failsafe-reports/TEST-*.xml`, **not** Maven's `Time elapsed`. See bug #9.

- [ ] **Step 3: Fill in the table**

```
| Strategy      | 150 withdrawals | 100 opposite transfers | Retries | Correct? |
|---------------|-----------------|------------------------|---------|----------|
| pessimistic   | 11.974 s        | 13.019 s               | 0       | yes      |
| optimistic    | _____           | _____                  | _____   | _____    |
| serializable  | _____           | _____                  | _____   | _____    |
| unsafe        | _____           | _____                  | n/a     | NO       |
```

**Report the numbers you measure, whatever they are.** If optimistic turns out faster than pessimistic on this workload, say so — that is a finding, not a problem. The purpose of this phase is to replace a guess with a measurement, and a measurement that only confirms what you already believed has not been tested.

- [ ] **Step 4: Write the results into `docs/hoc/HOC_DONG_THOI_VA_KHOA.md`**

Replace the placeholder in section 2 with the real table and a paragraph on what the numbers show.

- [ ] **Step 5: Commit**

---

## Task 8: Isolation phenomena

**Files:**
- Create: `backend/src/test/java/com/walletledger/ledger/IsolationPhenomenaIT.java`

Three tests, each demonstrating a phenomenon and which isolation level prevents it. Each uses two connections driven by latches.

| Phenomenon | READ COMMITTED | REPEATABLE READ | SERIALIZABLE |
|---|---|---|---|
| Dirty read | prevented | prevented | prevented |
| Non-repeatable read | **occurs** | prevented | prevented |
| Phantom read | **occurs** | prevented in PostgreSQL | prevented |

- [ ] **Step 1: Demonstrate a non-repeatable read at READ COMMITTED**

Transaction A reads a balance, transaction B commits a change, transaction A reads again and sees a different value.

- [ ] **Step 2: Show REPEATABLE READ prevents it**

Same script, different isolation level; the second read returns the first value.

- [ ] **Step 3: Note what PostgreSQL does differently**

PostgreSQL's REPEATABLE READ prevents phantom reads too, which the SQL standard does not require. Worth stating explicitly, since interview answers often quote the standard table rather than what PostgreSQL actually does.

- [ ] **Step 4: Commit**

---

## Definition of done for Phase 1C

- [ ] `mvnd -B verify` green, no skipped tests
- [ ] JaCoCo gate enforced at 85% instruction / 75% branch, with the **measured** figure recorded in this file
- [ ] Three ArchUnit rules passing, with any rule failure fixed in the **code** rather than in the rule
- [ ] Four `BalanceMutator` implementations, all running the **same** test contract
- [ ] `UnsafeBalanceMutator` reachable only from test sources, and demonstrably losing money
- [ ] The comparison table filled in with numbers read from `<testcase time=...>`, not from Maven's output
- [ ] `and version=?` observed in the SQL log for the optimistic strategy — not assumed
- [ ] `40001` observed in the log for the serializable strategy — not assumed
- [ ] `docs/hoc/HOC_DONG_THOI_VA_KHOA.md` updated with the real table
- [ ] Every new real bug recorded in `docs/hoc/NHAT_KY_BUG.md`
- [ ] No container belonging to another project was stopped

## Notes for the implementer

- **Do not run git.** Hand the owner one-line commit commands. No `Co-Authored-By` trailer.
- **Do not lower a gate or weaken a rule to make a build pass.** If a gate is wrong, say so and explain why.
- **Measure, do not assert.** Every number in this plan marked `____` is a fact to be observed and written down, and Phase 1C's entire value is that those numbers are real.
- **Observe the SQL, do not trust the annotation.** Bug #7 was `@Lock(PESSIMISTIC_WRITE)` producing SQL nobody had looked at. Tasks 5 and 6 repeat that trap twice; both have an explicit observation step for that reason.
- **The unsafe strategy failing is a success.** If it stops reproducing, investigate rather than delete.
