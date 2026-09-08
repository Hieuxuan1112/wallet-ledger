# Phase 1A — Foundation, Auth and Wallet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the backend skeleton, the database schema with its three ledger guarantees, and full JWT authentication, ending with an authenticated user who owns exactly one wallet holding a balance of zero.

**Architecture:** A single Spring Boot service layered `web → service → repository → domain`, with PostgreSQL owning the money invariants through `CHECK` constraints and triggers. Flyway owns the schema; JPA validates against it and never generates it. Every integration test runs against a real PostgreSQL 16 in Testcontainers.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Data JPA, Spring Security, JJWT 0.13.0, Flyway 11.7.2 (+ `flyway-database-postgresql`), PostgreSQL 16, JUnit 5, Testcontainers 1.21.4 (managed by Boot), ArchUnit 1.5.0, Docker Compose.

**Scope boundary:** No money movement in this plan. Deposits, withdrawals, transfers, idempotency, the balance-mutation strategies and all concurrency tests belong to Phase 1B. Outbox and Kafka belong to Phase 2.

---

## Environment

This workstation has **no JDK and no Maven**. Every build runs in a container, from **PowerShell** — Git Bash rewrites `/app` into a Windows path and breaks the mount. Paste this once per PowerShell session:

```powershell
function mvnd { docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" -v "//var/run/docker.sock:/var/run/docker.sock" -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true --add-host host.docker.internal:host-gateway -w /app maven:3.9-eclipse-temurin-21 mvn @args }
```

Then the build is `mvnd -B verify`. The first run downloads dependencies into the `wallet-m2` volume and takes several minutes; later runs are much faster.

**Git:** the repository owner runs every `git add` / `commit` / `push` themselves, in **cmd.exe**. Commit commands in this plan are therefore written as a **single line with repeated `-m` flags** — cmd.exe treats a newline inside a quoted string as a new command and silently truncates the message. The implementing agent must not run git write commands.

**Ports:** 80, 3000, 4000, 5000–5003, 8000, 8080–8082, 8090, 8501, 9090, 10002 and 16686 are taken by 52 other containers on this machine. Nothing here may stop them.

---

## File structure

| File | Responsibility |
|---|---|
| `.gitignore` | Keep secrets and build output out of git |
| `.env.example` | Names every variable; ships with empty secrets |
| `docker-compose.yml` | PostgreSQL for local development |
| `backend/pom.xml` | Dependencies and build plugins |
| `backend/src/main/resources/application.yml` | Configuration; secrets have no defaults |
| `backend/src/main/resources/db/migration/V1__auth.sql` | `app_user`, `refresh_token` |
| `backend/src/main/resources/db/migration/V2__ledger.sql` | `account`, `ledger_transaction`, `ledger_entry`, the three guarantees, system accounts |
| `backend/src/main/java/com/walletledger/WalletLedgerApplication.java` | Entry point |
| `backend/src/main/java/com/walletledger/config/JwtProperties.java` | Validated JWT configuration |
| `backend/src/main/java/com/walletledger/auth/` | `AppUser`, `RefreshToken`, repositories, `AuthService`, `TokenService`, `RefreshTokenRevoker`, `AuthController` |
| `backend/src/main/java/com/walletledger/account/` | `Account`, `AccountType`, `AccountRepository`, `WalletService`, `WalletController` |
| `backend/src/main/java/com/walletledger/ledger/` | `LedgerTransaction`, `LedgerEntry`, repositories |
| `backend/src/main/java/com/walletledger/security/` | `SecurityConfig`, `JwtAuthenticationFilter`, `ProblemDetailAuthenticationEntryPoint` |
| `backend/src/test/java/com/walletledger/AbstractIntegrationTest.java` | Shared Testcontainers PostgreSQL and test properties |

Each class has one responsibility. `service` classes must not import anything from `jakarta.servlet` or `org.springframework.web`; this is checked by ArchUnit in Phase 1B.

---

## Task 1: Project skeleton and toolchain proof

Nothing here is business logic. The point is to prove that Docker 29, Testcontainers 1.21.4 and the containerised Maven all work together **before** any real code depends on them.

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `docker-compose.yml`
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/walletledger/WalletLedgerApplication.java`
- Create: `backend/src/test/java/com/walletledger/AbstractIntegrationTest.java`
- Test: `backend/src/test/java/com/walletledger/ToolchainIT.java`

- [ ] **Step 1: Create `.gitignore`**

```gitignore
target/
node_modules/
dist/
.env
HANDOFF_CONTEXT.md
*.log
.idea/
.vscode/
```

- [ ] **Step 2: Create `.env.example`**

```dotenv
# Ports. Chosen to avoid the 52 containers already running on this workstation.
WEB_PORT=8095
API_PORT=8091
DB_PORT=55433

# Database
POSTGRES_DB=wallet
POSTGRES_USER=wallet
POSTGRES_PASSWORD=

# JWT signing key, at least 32 characters. Deliberately empty here:
# a missing value must stop startup, never fall back to a default.
APP_JWT_SECRET=
```

- [ ] **Step 3: Create `docker-compose.yml`**

The `:?` syntax makes Compose refuse to start with a clear message when the password is missing, instead of silently creating a database nobody can reach.

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-wallet}
      POSTGRES_USER: ${POSTGRES_USER:-wallet}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
    ports:
      - "${DB_PORT:-55433}:5432"
    volumes:
      - db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-wallet} -d ${POSTGRES_DB:-wallet}"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  db-data:
```

- [ ] **Step 4: Create `backend/pom.xml`**

Flyway 11 moved PostgreSQL support into its own artifact, so `flyway-database-postgresql` must be declared explicitly — without it Flyway starts and then reports that it does not support the `postgresql` dialect. Testcontainers is **not** given a version: Spring Boot 3.5.16 manages 1.21.4, which is exactly the minimum this machine needs for Docker 29.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>
    <relativePath/>
  </parent>

  <groupId>com.walletledger</groupId>
  <artifactId>wallet-ledger</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>wallet-ledger</name>

  <properties>
    <java.version>21</java.version>
    <jjwt.version>0.13.0</jjwt.version>
    <archunit.version>1.5.0</archunit.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>${archunit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-failsafe-plugin</artifactId>
        <executions>
          <execution>
            <goals>
              <goal>integration-test</goal>
              <goal>verify</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Naming convention that follows from this: classes ending in `Test` run under Surefire (`mvnd -B test`), classes ending in `IT` run under Failsafe (`mvnd -B verify`).

- [ ] **Step 5: Create `backend/src/main/resources/application.yml`**

`APP_JWT_SECRET` has no default on purpose: it signs tokens, and a default baked into the jar is how the previous project leaked a usable key. The database password does have an empty default here because its absence is already caught one layer out, by Compose refusing to start.

```yaml
spring:
  application:
    name: wallet-ledger
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:55433}/${POSTGRES_DB:wallet}
    username: ${POSTGRES_USER:wallet}
    password: ${POSTGRES_PASSWORD:}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true

server:
  port: ${API_PORT:8091}

app:
  jwt:
    secret: ${APP_JWT_SECRET}
    access-token-ttl: PT15M
    refresh-token-ttl: P7D
```

`ddl-auto: validate` matters: Flyway owns the schema, and Hibernate is only allowed to check that its entities match. If they drift, startup fails instead of quietly altering a money table.

- [ ] **Step 6: Create `backend/src/main/java/com/walletledger/WalletLedgerApplication.java`**

```java
package com.walletledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WalletLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletLedgerApplication.class, args);
    }
}
```

- [ ] **Step 7: Create `backend/src/test/java/com/walletledger/AbstractIntegrationTest.java`**

The JWT secret is built at runtime rather than written as a literal. A hard-coded string here would be a real credential to a secret scanner, and the previous project learned that scanner noise hides real findings.

```java
package com.walletledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    // Testcontainers' JUnit extension is deliberately NOT used. It owns a static container
    // per test class and stops it in afterAll, while Spring reuses one cached context across
    // every class sharing this configuration: the first class passes, and from the second on
    // the cached DataSource points at a dead port. Tying the container to the JVM instead
    // matches the lifetime of the cached context. Ryuk is disabled here, so the shutdown hook
    // is what actually stops it.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-".repeat(10));
    }

    @Autowired
    protected MockMvc mockMvc;
}
```

- [ ] **Step 8: Write the failing test — `backend/src/test/java/com/walletledger/ToolchainIT.java`**

```java
package com.walletledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ToolchainIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void springContextStartsAgainstARealPostgres() {
        String version = jdbcTemplate.queryForObject("select version()", String.class);
        assertThat(version).contains("PostgreSQL 16");
    }
}
```

- [ ] **Step 9: Run the build and confirm it passes**

Run: `mvnd -B verify`

Expected: `BUILD SUCCESS`, with `Tests run: 1, Failures: 0` for `ToolchainIT`.

If it fails with `Could not find a valid Docker environment`, the Docker socket mount is wrong — recheck the `mvnd` function. If it fails with a Ryuk timeout, confirm `TESTCONTAINERS_RYUK_DISABLED=true` reached the container.

- [ ] **Step 10: Commit**

```bash
git add .gitignore .env.example docker-compose.yml backend
```

```bash
git commit -m "chore: scaffold Spring Boot 3.5.16 backend with Testcontainers" -m "Pins Spring Boot 3.5.16, which manages Testcontainers 1.21.4 (the minimum that works with Docker 29). Adds flyway-database-postgresql, required since Flyway 10 split dialect support out of the core artifact. ToolchainIT proves the containerised Maven, the Docker socket mount and a real PostgreSQL 16 all work before any business code depends on them." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: Auth schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__auth.sql`
- Test: `backend/src/test/java/com/walletledger/auth/AuthSchemaIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

        assertThat(jdbc.queryForObject("select count(*) from refresh_token", Integer.class)).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=AuthSchemaIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL with `relation "app_user" does not exist`.

- [ ] **Step 3: Write `V1__auth.sql`**

`GENERATED BY DEFAULT AS IDENTITY` is used rather than `BIGSERIAL` because it is the SQL-standard form and, unlike `GENERATED ALWAYS`, it still allows the explicit id inserts that Task 3 needs for seeding system accounts.

```sql
CREATE TABLE app_user (
    id            BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE refresh_token (
    id         BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id),
    token_hash CHAR(64)    NOT NULL,
    family_id  UUID        NOT NULL,
    used_at    TIMESTAMPTZ NULL,
    revoked_at TIMESTAMPTZ NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_user ON refresh_token (user_id);
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=AuthSchemaIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V1__auth.sql backend/src/test/java/com/walletledger/auth/AuthSchemaIT.java
```

```bash
git commit -m "feat: add auth schema with Flyway V1" -m "Creates app_user and refresh_token. Username uniqueness, the role whitelist and refresh-token hash uniqueness are enforced by constraints rather than by application code, so they hold even for manual SQL." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: Ledger schema and its three guarantees

This is the most important task in Phase 1A. Everything else is ordinary web plumbing; these constraints are what make the ledger trustworthy.

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__ledger.sql`
- Test: `backend/src/test/java/com/walletledger/ledger/LedgerSchemaIT.java`

- [ ] **Step 1: Write the failing test**

Note `assertThatThrownBy` around an explicit transaction for the deferred check: the violation surfaces at **commit**, not at insert, which is the whole point of the constraint.

```java
package com.walletledger.ledger;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long newWallet() {
        Long userId = jdbc.queryForObject(
                "insert into app_user (username, password_hash) values (md5(random()::text), 'hash') returning id",
                Long.class);
        return jdbc.queryForObject(
                "insert into account (type, owner_user_id) values ('USER_WALLET', ?) returning id",
                Long.class, userId);
    }

    private long newTransaction() {
        Long userId = jdbc.queryForObject(
                "insert into app_user (username, password_hash) values (md5(random()::text), 'hash') returning id",
                Long.class);
        return jdbc.queryForObject(
                "insert into ledger_transaction (public_id, type, initiated_by_user_id) "
                        + "values (gen_random_uuid(), 'DEPOSIT', ?) returning id",
                Long.class, userId);
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

        assertThat(jdbc.queryForObject("select balance from account where id = 1", java.math.BigDecimal.class))
                .isEqualByComparingTo("-500");
    }

    @Test
    void oneUserCannotOwnTwoWallets() {
        Long userId = jdbc.queryForObject(
                "insert into app_user (username, password_hash) values ('carol', 'hash') returning id",
                Long.class);
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
    void aBalancedTransactionCommits() {
        long tx = newTransaction();
        long wallet = newWallet();

        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, 1, -25)", tx);
            jdbc.update("insert into ledger_entry (transaction_id, account_id, amount) values (?, ?, 25)", tx, wallet);
        });

        assertThat(jdbc.queryForObject("select sum(amount) from ledger_entry", java.math.BigDecimal.class))
                .isEqualByComparingTo("0");
    }

    @Test
    void aTransactionCannotBeReversedTwice() {
        long original = newTransaction();
        Long userId = jdbc.queryForObject("select initiated_by_user_id from ledger_transaction where id = ?",
                Long.class, original);
        jdbc.update("insert into ledger_transaction (public_id, type, reverses_transaction_id, initiated_by_user_id) "
                + "values (gen_random_uuid(), 'REVERSAL', ?, ?)", original, userId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into ledger_transaction (public_id, type, reverses_transaction_id, initiated_by_user_id) "
                        + "values (gen_random_uuid(), 'REVERSAL', ?, ?)", original, userId))
                .hasStackTraceContaining("ledger_transaction_reverses_transaction_id_key");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=LedgerSchemaIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL with `relation "account" does not exist`.

- [ ] **Step 3: Write `V2__ledger.sql`**

```sql
CREATE TABLE account (
    id            BIGINT        GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    type          VARCHAR(32)   NOT NULL,
    owner_user_id BIGINT        NULL REFERENCES app_user (id),
    balance       NUMERIC(19,4) NOT NULL DEFAULT 0,
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_account_type CHECK (type IN ('USER_WALLET', 'SYSTEM_FUNDING', 'SYSTEM_PAYOUT')),
    CONSTRAINT ck_wallet_owner CHECK ((type = 'USER_WALLET') = (owner_user_id IS NOT NULL)),
    CONSTRAINT ck_wallet_non_negative CHECK (type <> 'USER_WALLET' OR balance >= 0)
);

-- one wallet per user; system accounts are exempt because owner_user_id is null
CREATE UNIQUE INDEX uq_wallet_owner ON account (owner_user_id) WHERE type = 'USER_WALLET';

INSERT INTO account (id, type, owner_user_id, balance)
VALUES (1, 'SYSTEM_FUNDING', NULL, 0),
       (2, 'SYSTEM_PAYOUT',  NULL, 0);
SELECT setval(pg_get_serial_sequence('account', 'id'), 2);

CREATE TABLE ledger_transaction (
    id                      BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    public_id               UUID        NOT NULL UNIQUE,
    type                    VARCHAR(16) NOT NULL,
    reverses_transaction_id BIGINT      NULL UNIQUE REFERENCES ledger_transaction (id),
    initiated_by_user_id    BIGINT      NOT NULL REFERENCES app_user (id),
    description             VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_transaction_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT ck_reversal_link CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL))
);

CREATE TABLE ledger_entry (
    id             BIGINT        GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT        NOT NULL REFERENCES ledger_transaction (id),
    account_id     BIGINT        NOT NULL REFERENCES account (id),
    amount         NUMERIC(19,4) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_entry_amount_non_zero CHECK (amount <> 0)
);

CREATE INDEX idx_entry_transaction ON ledger_entry (transaction_id);
CREATE INDEX idx_entry_account_created ON ledger_entry (account_id, created_at DESC, id DESC);

-- Guarantee 2: the ledger is append-only. Corrections are posted as reversing
-- transactions, never as edits. A future migration that must touch this table
-- has to drop and recreate this trigger explicitly.
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only; % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- Guarantee 3: every transaction balances. A normal constraint fires per row and
-- would reject the first entry of every pair, so this one is deferred to COMMIT,
-- by which time both sides of the entry exist.
CREATE OR REPLACE FUNCTION assert_transaction_balanced() RETURNS trigger AS $$
DECLARE
    total NUMERIC(19,4);
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total
      FROM ledger_entry
     WHERE transaction_id = NEW.transaction_id;

    IF total <> 0 THEN
        RAISE EXCEPTION 'Unbalanced transaction %: entries sum to %', NEW.transaction_id, total
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_transaction_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_balanced();
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=LedgerSchemaIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 8, Failures: 0`.

If `anUnbalancedTransactionIsRejectedAtCommit` fails because the exception arrives at the `insert` rather than at commit, the trigger was created without `DEFERRABLE INITIALLY DEFERRED` — that keyword is the entire mechanism.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__ledger.sql backend/src/test/java/com/walletledger/ledger/LedgerSchemaIT.java
```

```bash
git commit -m "feat: add ledger schema with three database-level guarantees" -m "Signed-amount entries, so the invariant is SUM(amount)=0 in one query. Wallets cannot go negative (CHECK); entries cannot be updated or deleted (trigger); and every transaction must balance, verified by a DEFERRABLE INITIALLY DEFERRED constraint trigger that runs at COMMIT rather than per row. System funding and payout accounts are seeded with fixed ids 1 and 2 and are allowed to go negative." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: Domain entities and repositories

**Files:**
- Create: `backend/src/main/java/com/walletledger/auth/AppUser.java`
- Create: `backend/src/main/java/com/walletledger/auth/Role.java`
- Create: `backend/src/main/java/com/walletledger/auth/AppUserRepository.java`
- Create: `backend/src/main/java/com/walletledger/account/AccountType.java`
- Create: `backend/src/main/java/com/walletledger/account/Account.java`
- Create: `backend/src/main/java/com/walletledger/account/AccountRepository.java`
- Test: `backend/src/test/java/com/walletledger/account/AccountPersistenceIT.java`

- [ ] **Step 1: Write the failing test**

The version assertion is what makes optimistic locking possible in Phase 1B; without a mapped `@Version` column the strategy comparison cannot be built.

```java
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
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=AccountPersistenceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: compilation failure — `AppUser`, `Account` and the repositories do not exist yet.

- [ ] **Step 3: Write the enums**

`backend/src/main/java/com/walletledger/auth/Role.java`

```java
package com.walletledger.auth;

public enum Role {
    USER,
    ADMIN
}
```

`backend/src/main/java/com/walletledger/account/AccountType.java`

```java
package com.walletledger.account;

public enum AccountType {
    USER_WALLET,
    SYSTEM_FUNDING,
    SYSTEM_PAYOUT
}
```

- [ ] **Step 4: Write `AppUser`**

The no-argument constructor is `protected`: JPA requires one, but application code has no business creating an empty user.

```java
package com.walletledger.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    private AppUser(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public static AppUser create(String username, String passwordHash) {
        return new AppUser(username, passwordHash, Role.USER);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

- [ ] **Step 5: Write `Account`**

`credit` and `debit` are the only ways to change a balance. There is no `setBalance`, so no caller can put the entity into a state the ledger did not produce. The negative check here is a fast, friendly failure; the database `CHECK` remains the real guarantee.

```java
package com.walletledger.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountType type;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(nullable = false)
    private long version;

    protected Account() {
    }

    private Account(AccountType type, Long ownerUserId) {
        this.type = type;
        this.ownerUserId = ownerUserId;
    }

    public static Account walletFor(Long ownerUserId) {
        return new Account(AccountType.USER_WALLET, ownerUserId);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        BigDecimal next = this.balance.subtract(amount);
        if (type == AccountType.USER_WALLET && next.signum() < 0) {
            throw new IllegalStateException("Wallet balance would go negative");
        }
        this.balance = next;
    }

    public Long getId() {
        return id;
    }

    public AccountType getType() {
        return type;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }
}
```

- [ ] **Step 6: Write the repositories**

`backend/src/main/java/com/walletledger/auth/AppUserRepository.java`

```java
package com.walletledger.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
```

`backend/src/main/java/com/walletledger/account/AccountRepository.java`

```java
package com.walletledger.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByOwnerUserId(Long ownerUserId);

    Optional<Account> findByType(AccountType type);
}
```

- [ ] **Step 7: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=AccountPersistenceIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 3, Failures: 0`.

A failure mentioning `Schema-validation` means an entity does not match the migration — fix the entity, never the migration, because Flyway owns the schema.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/account/AccountPersistenceIT.java
```

```bash
git commit -m "feat: add AppUser and Account entities" -m "Balances change only through credit and debit; there is no setter, so callers cannot put an account into a state the ledger did not produce. Account carries a mapped @Version column, which Phase 1B needs to compare optimistic against pessimistic locking. Both entities keep a protected no-arg constructor for JPA and expose named factory methods instead." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 5: Configuration, security baseline and registration

**Files:**
- Create: `backend/src/main/java/com/walletledger/config/JwtProperties.java`
- Create: `backend/src/main/java/com/walletledger/security/SecurityConfig.java`
- Create: `backend/src/main/java/com/walletledger/auth/UsernameAlreadyTakenException.java`
- Create: `backend/src/main/java/com/walletledger/auth/RegisterRequest.java`
- Create: `backend/src/main/java/com/walletledger/auth/RegisterResponse.java`
- Create: `backend/src/main/java/com/walletledger/auth/AuthService.java`
- Create: `backend/src/main/java/com/walletledger/auth/AuthController.java`
- Test: `backend/src/test/java/com/walletledger/auth/RegistrationIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationIT extends AbstractIntegrationTest {

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AccountRepository accounts;

    private static String body(String username, String password) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, password);
    }

    @Test
    void registeringCreatesExactlyOneWalletAtZero() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("frank", "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("frank"));

        AppUser user = users.findByUsername("frank").orElseThrow();
        Account wallet = accounts.findByOwnerUserId(user.getId()).orElseThrow();

        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThat(accounts.findAll().stream()
                .filter(a -> user.getId().equals(a.getOwnerUserId()))
                .count()).isEqualTo(1);
    }

    @Test
    void thePasswordIsNeverStoredAsGiven() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("grace", "password123")))
                .andExpect(status().isCreated());

        String stored = users.findByUsername("grace").orElseThrow().getPasswordHash();

        assertThat(stored).doesNotContain("password123").startsWith("$2");
    }

    @Test
    void aDuplicateUsernameIsRejectedWithConflict() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("heidi", "password123")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("heidi", "different123")))
                .andExpect(status().isConflict());
    }

    @Test
    void aShortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(body("ivan", "short")))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=RegistrationIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL — every request returns 401 or 404 because no controller and no security configuration exist yet.

- [ ] **Step 3: Write `JwtProperties`**

`@Validated` with `@Size(min = 32)` turns a missing or weak signing key into a startup failure that names the problem, instead of a service that quietly signs tokens with something guessable.

```java
package com.walletledger.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank
        @Size(min = 32, message = "APP_JWT_SECRET must be at least 32 characters")
        String secret,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl) {
}
```

- [ ] **Step 4: Write `SecurityConfig`**

CSRF is disabled because this API is stateless and carries no session cookie: there is no ambient credential for a cross-site request to ride on. The bearer token must be attached deliberately by the caller.

```java
package com.walletledger.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .build();
    }
}
```

- [ ] **Step 5: Write the request and response records**

`backend/src/main/java/com/walletledger/auth/RegisterRequest.java`

The 72-character cap is not arbitrary: BCrypt silently ignores anything past 72 bytes, so accepting a longer password would mean accepting one whose tail never matters.

```java
package com.walletledger.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                 message = "username may contain letters, digits, dot, dash and underscore only")
        String username,

        @NotBlank
        @Size(min = 8, max = 72)
        String password) {
}
```

`backend/src/main/java/com/walletledger/auth/RegisterResponse.java`

```java
package com.walletledger.auth;

public record RegisterResponse(long userId, String username) {
}
```

- [ ] **Step 6: Write `UsernameAlreadyTakenException`**

Extending `ErrorResponseException` makes Spring render RFC 7807 without a dedicated handler.

```java
package com.walletledger.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class UsernameAlreadyTakenException extends ErrorResponseException {

    public UsernameAlreadyTakenException(String username) {
        super(HttpStatus.CONFLICT, problem(username), null);
    }

    private static ProblemDetail problem(String username) {
        ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        detail.setTitle("Username already taken");
        detail.setDetail("Username '%s' is already registered".formatted(username));
        return detail;
    }
}
```

- [ ] **Step 7: Write `AuthService`**

The `existsByUsername` check is a courtesy, not the guarantee. Two simultaneous registrations both see "available" and both insert; the unique index rejects one of them, and that rejection is translated to the same 409. This is the first place in the codebase where a check-then-act race appears — the whole of Phase 1B is about the same shape of problem with money.

```java
package com.walletledger.auth;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository users, AccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String username, String rawPassword) {
        if (users.existsByUsername(username)) {
            throw new UsernameAlreadyTakenException(username);
        }
        try {
            AppUser user = users.saveAndFlush(
                    AppUser.create(username, passwordEncoder.encode(rawPassword)));
            accounts.saveAndFlush(Account.walletFor(user.getId()));
            return user;
        } catch (DataIntegrityViolationException e) {
            throw new UsernameAlreadyTakenException(username);
        }
    }
}
```

- [ ] **Step 8: Write `AuthController`**

```java
package com.walletledger.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        AppUser user = authService.register(request.username(), request.password());
        return new RegisterResponse(user.getId(), user.getUsername());
    }
}
```

- [ ] **Step 9: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=RegistrationIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger/auth/RegistrationIT.java
```

```bash
git commit -m "feat: add registration with BCrypt and automatic wallet creation" -m "JwtProperties is @Validated with @Size(min = 32), so a missing or weak APP_JWT_SECRET stops startup instead of silently signing tokens. Registration creates the user and the wallet in one transaction. The existsByUsername check is a courtesy only: two simultaneous registrations both pass it, and the unique index is what actually decides, so the resulting DataIntegrityViolationException is translated to the same 409." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 6: Login, JWT filter and the first protected endpoint

The wallet endpoint is introduced here rather than later because the authentication filter needs something real to protect.

**Files:**
- Create: `backend/src/main/java/com/walletledger/auth/AuthenticatedUser.java`
- Create: `backend/src/main/java/com/walletledger/auth/TokenService.java`
- Create: `backend/src/main/java/com/walletledger/auth/LoginRequest.java`
- Create: `backend/src/main/java/com/walletledger/auth/LoginResponse.java`
- Create: `backend/src/main/java/com/walletledger/security/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/walletledger/account/WalletView.java`
- Create: `backend/src/main/java/com/walletledger/account/WalletService.java`
- Create: `backend/src/main/java/com/walletledger/account/WalletController.java`
- Modify: `backend/src/main/java/com/walletledger/auth/AuthService.java` — add `login`
- Modify: `backend/src/main/java/com/walletledger/auth/AuthController.java` — add `POST /login`
- Modify: `backend/src/main/java/com/walletledger/security/SecurityConfig.java` — register the filter
- Modify: `backend/src/test/java/com/walletledger/AbstractIntegrationTest.java` — add shared helpers
- Test: `backend/src/test/java/com/walletledger/auth/LoginIT.java`

- [ ] **Step 1: Add shared helpers to `AbstractIntegrationTest`**

Put the register-then-login helper in the base class from the start. In the previous project five test classes each carried their own copy of a username/password literal, which set off five secret-scanner alerts — and the alerts were a symptom of the duplication, not of a real credential.

Replace the body of the class with:

```java
package com.walletledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    // Testcontainers' JUnit extension is deliberately NOT used. It owns a static container
    // per test class and stops it in afterAll, while Spring reuses one cached context across
    // every class sharing this configuration: the first class passes, and from the second on
    // the cached DataSource points at a dead port. Tying the container to the JVM instead
    // matches the lifetime of the cached context. Ryuk is disabled here, so the shutdown hook
    // is what actually stops it.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-".repeat(10));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Every test account uses this; there is exactly one literal in the whole test source tree. */
    protected static final String TEST_PASSWORD = "not-a-real-" + "secret-42";

    protected String credentials(String username) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, TEST_PASSWORD);
    }

    protected void register(String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(APPLICATION_JSON)
                .content(credentials(username)));
    }

    protected JsonNode loginTokens(String username) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(credentials(username)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    protected String accessTokenFor(String username) throws Exception {
        register(username);
        return loginTokens(username).get("accessToken").asText();
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginIT extends AbstractIntegrationTest {

    @Test
    void aValidTokenReachesTheWalletEndpoint() throws Exception {
        String token = accessTokenFor("judy");

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("0.0000"));
    }

    @Test
    void noTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/wallet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = accessTokenFor("mallory");
        String tampered = token.substring(0, token.length() - 2) + "xy";

        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongPasswordAndAnUnknownUserAreIndistinguishable() throws Exception {
        register("niaj");

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"niaj","password":"wrong-password-here"}"""))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(credentials("nobody-at-all")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownUser);
    }

    @Test
    void oneUserCannotSeeAnotherUsersWallet() throws Exception {
        String oscarToken = accessTokenFor("oscar");
        register("peggy");

        // the wallet endpoint takes no identifier at all; identity comes from the token
        mockMvc.perform(get("/api/v1/wallet").header(HttpHeaders.AUTHORIZATION, bearer(oscarToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("0.0000"));
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=LoginIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL — `/api/v1/auth/login` returns 404 and `/api/v1/wallet` does not exist.

- [ ] **Step 4: Write `AuthenticatedUser` and `TokenService`**

`backend/src/main/java/com/walletledger/auth/AuthenticatedUser.java`

```java
package com.walletledger.auth;

public record AuthenticatedUser(long id, String username, Role role) {
}
```

`backend/src/main/java/com/walletledger/auth/TokenService.java`

```java
package com.walletledger.auth;

import com.walletledger.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class TokenService {

    private final JwtProperties properties;
    private final SecretKey key;

    public TokenService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AuthenticatedUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

If any of `subject`, `issuedAt`, `expiration`, `signWith` or `verifyWith` does not compile, JJWT 0.13.0 changed the 0.12 builder API. Fix the call sites and record it as a bug entry; do not downgrade the dependency silently.

- [ ] **Step 5: Write the login records**

`backend/src/main/java/com/walletledger/auth/LoginRequest.java`

```java
package com.walletledger.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {
}
```

`backend/src/main/java/com/walletledger/auth/LoginResponse.java`

```java
package com.walletledger.auth;

public record LoginResponse(String accessToken, String refreshToken) {
}
```

`refreshToken` is populated in Task 7; until then it is issued as `null`, which the tests do not read.

- [ ] **Step 6: Add `login` to `AuthService`**

The dummy hash is the point of this method. Without it, an unknown username returns before BCrypt runs, so the response comes back measurably faster and an attacker can enumerate valid usernames by timing alone. The previous project had exactly this gap and documented it; here it is closed.

Add these fields and the method to `AuthService`:

```java
    private final TokenService tokenService;
    private final String dummyHash;

    // extend the constructor:
    public AuthService(AppUserRepository users, AccountRepository accounts,
                       PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-credential");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String username, String rawPassword) {
        Optional<AppUser> found = users.findByUsername(username);
        // Always run BCrypt, even when the user does not exist, so both paths cost the same.
        String hash = found.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);

        if (found.isEmpty() || !matches) {
            throw new BadCredentialsException("Invalid username or password");
        }
        return new LoginResponse(tokenService.issueAccessToken(found.get()), null);
    }
```

Add the imports `java.util.Optional` and `org.springframework.security.authentication.BadCredentialsException`.

- [ ] **Step 7: Add the login endpoint to `AuthController`**

```java
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }
```

- [ ] **Step 8: Write `JwtAuthenticationFilter`**

```java
package com.walletledger.security;

import com.walletledger.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final TokenService tokenService;

    public JwtAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            tokenService.verify(header.substring(PREFIX.length())).ifPresent(user -> {
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
```

An invalid token is not an error here — the filter simply leaves the context empty, and the authorisation rules produce the 401. This keeps one place responsible for rejecting requests.

- [ ] **Step 9: Register the filter in `SecurityConfig`**

Add the constructor and the `addFilterBefore` call:

```java
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }
```

and inside `filterChain`, immediately before `.build()`:

```java
                .addFilterBefore(jwtAuthenticationFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
```

- [ ] **Step 10: Write the wallet endpoint**

`backend/src/main/java/com/walletledger/account/WalletView.java`

```java
package com.walletledger.account;

import java.math.BigDecimal;

public record WalletView(long accountId, BigDecimal balance) {
}
```

`backend/src/main/java/com/walletledger/account/WalletService.java`

```java
package com.walletledger.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private final AccountRepository accounts;

    public WalletService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public WalletView forUser(long userId) {
        Account wallet = accounts.findByOwnerUserId(userId)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " has no wallet"));
        return new WalletView(wallet.getId(), wallet.getBalance());
    }
}
```

`backend/src/main/java/com/walletledger/account/WalletController.java`

The endpoint takes **no identifier**. The account is derived from the token, so there is no parameter an attacker could change to read someone else's wallet. The same rule reappears in Phase 4: the user id is attached by the server, never taken from input.

```java
package com.walletledger.account;

import com.walletledger.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping
    public WalletView get(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.forUser(user.id());
    }
}
```

- [ ] **Step 11: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=LoginIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 5, Failures: 0`.

If `noTokenIsRejected` reports 403 instead of 401, that is the expected default and Task 8 fixes it — mark this one test `@Disabled("fixed in Task 8")` rather than weakening the assertion, and re-enable it there.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/walletledger backend/src/test/java/com/walletledger
```

```bash
git commit -m "feat: add JWT login and the wallet endpoint" -m "Access tokens live 15 minutes and carry the user id, username and role. The wallet endpoint takes no identifier: identity comes from the token, so no parameter exists for a caller to change. Login always runs BCrypt, comparing against a dummy hash when the username is unknown, which closes the timing side channel that would otherwise let an attacker enumerate valid usernames." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 7: Refresh-token rotation with reuse detection

**Files:**
- Create: `backend/src/main/java/com/walletledger/auth/RefreshToken.java`
- Create: `backend/src/main/java/com/walletledger/auth/RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/walletledger/auth/RefreshTokenRevoker.java`
- Create: `backend/src/main/java/com/walletledger/auth/RefreshTokenService.java`
- Create: `backend/src/main/java/com/walletledger/auth/RefreshRequest.java`
- Modify: `backend/src/main/java/com/walletledger/auth/AuthService.java` — issue a refresh token on login, add `refresh` and `logout`
- Modify: `backend/src/main/java/com/walletledger/auth/AuthController.java` — add `POST /refresh` and `POST /logout`
- Test: `backend/src/test/java/com/walletledger/auth/RefreshTokenIT.java`

- [ ] **Step 1: Write the failing test**

The third test is the important one. In the previous project the family revocation was performed and then destroyed by the very exception that rejected the request, because both ran in the same transaction. Asserting on the database state *after* the rejection is what catches that.

```java
package com.walletledger.auth;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshTokenIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}""".formatted(refreshToken);
    }

    @Test
    void loginReturnsBothTokens() throws Exception {
        register("rupert");

        mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("rupert")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void refreshingRotatesTheToken() throws Exception {
        register("sybil");
        String first = loginTokens("sybil").get("refreshToken").asText();

        String second = objectMapper.readTree(
                        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                                        .content(refreshBody(first)))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void replayingAUsedTokenRevokesTheWholeFamilyAndTheRevocationSurvives() throws Exception {
        register("trent");
        String first = loginTokens("trent").get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody(first))).andExpect(status().isOk());

        // the same token again: this is either theft or a broken client, and we cannot tell which
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody(first))).andExpect(status().isUnauthorized());

        Integer stillUsable = jdbc.queryForObject(
                "select count(*) from refresh_token where revoked_at is null", Integer.class);
        assertThat(stillUsable).isZero();
    }

    @Test
    void logoutLeavesOtherSessionsAlone() throws Exception {
        register("uma");
        String sessionOne = loginTokens("uma").get("refreshToken").asText();
        String sessionTwo = loginTokens("uma").get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout").contentType(APPLICATION_JSON)
                .content(refreshBody(sessionOne))).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody(sessionTwo))).andExpect(status().isOk());
    }

    @Test
    void anUnknownRefreshTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(APPLICATION_JSON)
                .content(refreshBody("this-token-was-never-issued"))).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=RefreshTokenIT" "-DfailIfNoSpecifiedTests=false"`

Expected: FAIL — `$.refreshToken` is null and `/api/v1/auth/refresh` returns 404.

- [ ] **Step 3: Write the `RefreshToken` entity**

```java
package com.walletledger.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, UUID familyId, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }
}
```

- [ ] **Step 4: Write `RefreshTokenRepository`**

```java
package com.walletledger.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
```

- [ ] **Step 5: Write `RefreshTokenRevoker`**

This must be its own bean. Calling a `REQUIRES_NEW` method on `this` goes straight to the method and skips the Spring proxy, so it silently runs inside the caller's transaction and is rolled back along with it. The whole point is that the revocation commits independently of the rejection.

```java
package com.walletledger.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class RefreshTokenRevoker {

    private final RefreshTokenRepository tokens;

    public RefreshTokenRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, Instant now) {
        return tokens.revokeFamily(familyId, now);
    }
}
```

- [ ] **Step 6: Write `RefreshTokenService`**

Only the SHA-256 hash is stored. The token is 256 random bits, so there is no dictionary to attack and no reason to pay for BCrypt — and BCrypt's per-value salt would make the hash unindexable, turning every lookup into a full scan.

```java
package com.walletledger.auth;

import com.walletledger.config.JwtProperties;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository tokens;
    private final RefreshTokenRevoker revoker;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenRevoker revoker,
                               JwtProperties properties) {
        this.tokens = tokens;
        this.revoker = revoker;
        this.properties = properties;
    }

    public String issue(long userId, UUID familyId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.save(new RefreshToken(userId, hash(token), familyId,
                Instant.now().plus(properties.refreshTokenTtl())));
        return token;
    }

    @Transactional
    public RefreshToken consume(String presented) {
        Instant now = Instant.now();
        RefreshToken stored = tokens.findByTokenHash(hash(presented))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (!stored.isUsable(now)) {
            // Either the token was stolen and is being replayed, or a legitimate client
            // got out of step. The two are indistinguishable, so the safe move is to
            // invalidate the whole family and make everyone log in again.
            revoker.revokeFamily(stored.getFamilyId(), now);
            throw new BadCredentialsException("Invalid refresh token");
        }
        stored.markUsed(now);
        return stored;
    }

    @Transactional
    public void revokeFamilyOf(String presented) {
        tokens.findByTokenHash(hash(presented))
                .ifPresent(token -> revoker.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
```

- [ ] **Step 7: Wire refresh into `AuthService`**

Replace the constructor so the new collaborator is injected, then change `login` and add the two new
methods. The full constructor after this task:

```java
    private final RefreshTokenService refreshTokens;

    public AuthService(AppUserRepository users, AccountRepository accounts,
                       PasswordEncoder passwordEncoder, TokenService tokenService,
                       RefreshTokenService refreshTokens) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-credential");
    }
```

Then the methods:

```java
    @Transactional
    public LoginResponse login(String username, String rawPassword) {
        Optional<AppUser> found = users.findByUsername(username);
        String hash = found.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(rawPassword, hash);

        if (found.isEmpty() || !matches) {
            throw new BadCredentialsException("Invalid username or password");
        }
        AppUser user = found.get();
        String refreshToken = refreshTokens.issue(user.getId(), UUID.randomUUID());
        return new LoginResponse(tokenService.issueAccessToken(user), refreshToken);
    }

    @Transactional
    public LoginResponse refresh(String presentedRefreshToken) {
        RefreshToken consumed = refreshTokens.consume(presentedRefreshToken);
        AppUser user = users.findById(consumed.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        String rotated = refreshTokens.issue(user.getId(), consumed.getFamilyId());
        return new LoginResponse(tokenService.issueAccessToken(user), rotated);
    }

    @Transactional
    public void logout(String presentedRefreshToken) {
        refreshTokens.revokeFamilyOf(presentedRefreshToken);
    }
```

`login` is no longer `readOnly`. Add the import `java.util.UUID`. `familyId` ties one login session together, which is why logging out of one device leaves the others alone.

- [ ] **Step 8: Write `RefreshRequest` and the endpoints**

`backend/src/main/java/com/walletledger/auth/RefreshRequest.java`

```java
package com.walletledger.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {
}
```

Add to `AuthController`:

```java
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
```

- [ ] **Step 9: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=RefreshTokenIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 5, Failures: 0`.

If `replayingAUsedTokenRevokesTheWholeFamilyAndTheRevocationSurvives` reports `stillUsable` greater than zero, the revocation was rolled back with the rejection — check that `RefreshTokenRevoker` is injected as a bean and not called through `this`.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/walletledger/auth backend/src/test/java/com/walletledger/auth/RefreshTokenIT.java
```

```bash
git commit -m "feat: add refresh token rotation with reuse detection" -m "Refresh tokens are 256 random bits stored only as a SHA-256 hash; BCrypt would be the wrong tool because there is no dictionary to defend against and its per-value salt would make lookups unindexable. Replaying a used token revokes the entire token family, since theft and a broken client are indistinguishable. The revocation runs in RefreshTokenRevoker under REQUIRES_NEW, in its own bean, so it commits independently of the exception that rejects the request." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 8: Correct 401 and 403 as RFC 7807 problems

Spring Security's stateless default answers unauthenticated requests with 403, which says "I know who you are and you may not do this" when the truth is "I do not know who you are". The distinction matters to any client deciding whether to send the user to a login screen or to an error page.

**Files:**
- Create: `backend/src/main/java/com/walletledger/security/ProblemDetailAuthenticationEntryPoint.java`
- Modify: `backend/src/main/java/com/walletledger/security/SecurityConfig.java` — wire the entry point and the denied handler
- Modify: `backend/src/main/resources/application.yml` — enable problem details for framework exceptions
- Test: `backend/src/test/java/com/walletledger/security/ProblemDetailIT.java`

- [ ] **Step 1: Write the failing test**

```java
package com.walletledger.security;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProblemDetailIT extends AbstractIntegrationTest {

    @Test
    void anUnauthenticatedRequestGives401NotForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/wallet"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anAuthenticatedUserWithoutTheAdminRoleGives403() throws Exception {
        String token = accessTokenFor("victor");

        mockMvc.perform(get("/api/v1/admin/reconcile").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void aValidationFailureIsAlsoAProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"x","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
```

`/api/v1/admin/reconcile` does **not** need to exist for this test, and must not be created here — it
belongs to Phase 1B. Spring Security authorises before routing, so a non-admin token is rejected with
403 before anything looks for a handler. If this test ever starts returning 404, authorisation stopped
running, which is a more serious finding than a missing endpoint.

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvnd -B verify "-Dit.test=ProblemDetailIT" "-DfailIfNoSpecifiedTests=false"`

Expected: the first test fails with `expected:<401> but was:<403>`, and the third fails on content type.

- [ ] **Step 3: Write `ProblemDetailAuthenticationEntryPoint`**

One class implements both interfaces so that the two answers are written in one place and cannot drift apart.

```java
package com.walletledger.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ProblemDetailAuthenticationEntryPoint
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No credentials, or credentials we could not verify: the caller is unknown. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** Credentials were valid, but they do not permit this. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Access denied");
    }

    private void write(HttpServletResponse response, HttpStatus status, String title) throws IOException {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(title);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), detail);
    }
}
```

- [ ] **Step 4: Wire it into `SecurityConfig`**

Add the field and constructor parameter, then add this to the chain before `.build()`:

```java
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemDetailAuthenticationEntryPoint)
                        .accessDeniedHandler(problemDetailAuthenticationEntryPoint))
```

- [ ] **Step 5: Enable problem details for framework exceptions**

In `backend/src/main/resources/application.yml`, add under `spring:`:

```yaml
  mvc:
    problemdetails:
      enabled: true
```

Without this, a validation failure returns Spring Boot's older error body and the API speaks two different error formats depending on which layer rejected the request.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `mvnd -B verify "-Dit.test=ProblemDetailIT" "-DfailIfNoSpecifiedTests=false"`

Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 7: Re-enable anything disabled in Task 6**

If `LoginIT.noTokenIsRejected` was marked `@Disabled` in Task 6, remove the annotation now and run `mvnd -B verify` to confirm the whole suite is green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/walletledger/security backend/src/main/resources/application.yml backend/src/test/java/com/walletledger
```

```bash
git commit -m "feat: return RFC 7807 problems with correct 401 and 403 semantics" -m "Spring Security answers unauthenticated stateless requests with 403 by default, which tells the client the wrong thing: 401 means the caller is unknown, 403 means the caller is known and not permitted. One class implements both AuthenticationEntryPoint and AccessDeniedHandler so the two answers cannot drift apart. Enabling spring.mvc.problemdetails makes validation failures use the same format." -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Definition of done for Phase 1A

- [ ] `mvnd -B verify` is green with no skipped tests
- [ ] A registered user owns exactly one wallet with balance `0.0000`
- [ ] `select sum(amount) from ledger_entry` returns `0` (trivially — no entries yet)
- [ ] Attempting `update ledger_entry` fails with `append-only`
- [ ] Attempting an unbalanced transaction fails **at commit**, not at insert
- [ ] No container belonging to another project was stopped

Two guarantees are about *refusing to start*, so no test can assert them from inside a running
application. Verify both by hand, once, and paste the output into the bug/decision log.

**A missing signing key must stop startup and name the variable.** From `D:\wallet-ledger\backend`
in PowerShell:

```powershell
docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" -w /app maven:3.9-eclipse-temurin-21 mvn -q -B spring-boot:run
```

Expected: startup fails with a `ConfigurationPropertiesBindException` whose message contains
`APP_JWT_SECRET must be at least 32 characters`. A successful start is a **failure of this check** —
it means a default crept in somewhere.

**An empty database password must stop Compose.** From `D:\wallet-ledger`:

```powershell
docker compose --env-file .env.example config
```

Expected: Compose exits non-zero with `POSTGRES_PASSWORD is required`. `.env.example` ships with the
value empty precisely so this check is repeatable.

---

## Notes for the implementer

- **Do not run git.** Hand the owner the one-line commit commands exactly as written.
- **Do not weaken a database constraint to make a test pass.** If a constraint blocks something legitimate, the design is wrong and needs discussion, not a `DROP CONSTRAINT`.
- **If JJWT 0.13.0 differs from the 0.12 API** used in the previous project, the compile error appears in Task 6. Adjust the call sites; do not downgrade the dependency without saying why.
- **Record every real bug** — symptom, first (wrong) hypothesis, why it was wrong, the correct fix — in a running list. These become section 10 of `docs/hoc/KIEN_TRUC_VA_QUYET_DINH.md`, which was the most valuable part of the previous project.
