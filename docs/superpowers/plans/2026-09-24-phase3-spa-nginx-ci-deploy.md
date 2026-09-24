# Phase 3: React SPA, nginx, multi-instance proof, CI and deployment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a React 19 SPA and nginx in front of three API replicas, prove with `MultiInstanceIT` that money stays exact and every outbox event is published exactly once across three real JVMs (plus a `synchronized` control experiment that fails the same test), run it all in GitHub Actions, and ship multi-arch images to an Oracle VM — completing Phase 3's milestone per spec §13 ("Live URL; `MultiInstanceIT` green").

**Architecture:** One `docker-compose.yml` drives local, CI and production: `db`, `kafka`, `api` (scalable, no host port) and `web` (nginx-unprivileged serving the SPA and proxying `/api/` to an `upstream` that re-resolves Docker DNS, so `--scale api=3` is load-balanced). `docker-compose.prod.yml` only adds what production needs on top: three replicas, TLS on 443, and a certbot renewer. `MultiInstanceIT` is a plain JUnit test (no Spring, no Testcontainers) run from a Maven container attached to the compose network, so the identical command works on Windows and in CI.

**Tech Stack:** React 19.3 + TypeScript 6.0 + Vite 8.3 + Tailwind 4.3 (`@tailwindcss/vite`) + Vitest 5 + oxlint; `nginxinc/nginx-unprivileged:1.28-alpine`; `eclipse-temurin:21-jre-alpine`; Spring Boot Actuator (health only); GitHub Actions with `gitleaks/gitleaks-action@v3`, `docker/build-push-action@v6` + QEMU for `linux/amd64,linux/arm64`; GHCR (public); `certbot/certbot:v5.8.0`.

**Spec:** `docs/superpowers/specs/2026-09-08-wallet-ledger-design.md` — primarily §2 (architecture), §7 (outbox on 3 replicas), §9.1 (frontend scope and the client-generated `Idempotency-Key`), §10 (Multi-instance row), §11 (ports, secrets, Maven-in-Docker, deployment), §13 (Phase 3 milestone).

## Global Constraints

- Ports come from `.env`: `WEB_PORT=8095`, `API_PORT=8091`, `DB_PORT=55433`, `KAFKA_PORT=19092` (spec §11). DB and Kafka host ports bind to `127.0.0.1` only; the API has **no** host port (it is scaled, and only nginx talks to it).
- Secrets (`APP_JWT_SECRET`, `POSTGRES_PASSWORD`, later `GOOGLE_AI_API_KEY`) have **no default values** anywhere — not in `application.yml`, not in compose (`${VAR:?message}`), not in the Dockerfiles. Missing ⇒ startup fails naming the variable.
- `.env` is gitignored; only `.env.example` is committed, with secrets left empty.
- Every compose service has a healthcheck. Every image runs as non-root.
- CORS, rate limiting and security headers live in nginx. The SPA and API are same-origin behind nginx, so the CORS policy is "send no `Access-Control-Allow-*` headers at all" — browsers then refuse every cross-origin read.
- `double`/`float` stay forbidden in backend main sources (ArchUnit). Money stays a JSON **string** end-to-end; the SPA never parses an amount into a JS `number`.
- Testcontainers stays at the Boot-managed `1.21.4` (≥ floor). No version overrides.
- Coverage gate **85% instruction / 75% branch**, unchanged. `MultiInstanceIT` runs in its own Maven profile with JaCoCo skipped — it is not part of `mvn -B verify` and does not count toward coverage.
- Maven from Git Bash: prefix `MSYS_NO_PATHCONV=1`, check `docker info` first, and never run two `mvn verify` against `backend/` at once (NHAT_KY_BUG #12–#14).
- **Do not run git.** Each task ends with a `git add` + `git commit` block for the owner, **no** `Co-Authored-By` line.
- Windows has no `python3`; write files with the Write/Edit tools, not long heredocs.

### The Maven command used throughout (from Git Bash, repo root)

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" -v "//var/run/docker.sock:/var/run/docker.sock" -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true --add-host host.docker.internal:host-gateway -w /app maven:3.9-eclipse-temurin-21 mvn -B verify "-Dit.test=<ClassName>" "-DfailIfNoSpecifiedTests=false"
```

Written below as **`MVN_IT <ClassName>`**. The full suite is the same command without the two `-D` arguments, written **`MVN_FULL`**. Unit tests only: `MSYS_NO_PATHCONV=1 docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" -w /app maven:3.9-eclipse-temurin-21 mvn -B test -Dtest=<ClassName>`, written **`MVN_UNIT <ClassName>`**.

## Task 0: Preconditions (owner, before any task)

1. **Free space on `C:`** — it is at 0 GB, and Docker Desktop cannot start (`docker info` → "Docker Desktop is unable to start"). Measured: `%LOCALAPPDATA%\Docker\wsl\disk\docker_data.vhdx` 47.3 GB, `%LOCALAPPDATA%\Temp` 5.3 GB, `Downloads` 6.1 GB. Durable fix: Docker Desktop → Settings → Resources → Advanced → *Disk image location* → a folder on `D:` (107 GB free). Short-term: empty `%LOCALAPPDATA%\Temp`. Then `docker info` must print a server version.
2. Nothing from Phase 1C/2 is on `main` yet (`origin/main` = `fb9ec27`; `phase2a-statement-refund` is 18 commits ahead; `gh pr list --state all` is empty). Commit the two untracked plan files, open the Phase 1C+2 PR, then branch Phase 3 **from `phase2a-statement-refund`** (stacked):

```bash
git add docs/superpowers/plans/2026-09-23-phase2a-statement-and-refund.md docs/superpowers/plans/2026-09-23-phase2b-outbox-kafka-notifications.md
git commit -m "docs: add the Phase 2A and 2B implementation plans"
git push origin phase2a-statement-refund
```

```bash
git checkout -b phase3-spa-nginx-ci-deploy
```

(`gh` lives at `C:\Program Files\GitHub CLI\gh.exe` and is logged in as `Hieuxuan1112`; it is just not on the Git Bash `PATH`.)

## File Structure

```
backend/
  pom.xml                                   MODIFY: actuator; failsafe excludes tag multi-instance; profile multi-instance
  Dockerfile                                NEW: build stage on $BUILDPLATFORM, JRE alpine runtime, non-root
  .dockerignore                             NEW
  src/main/resources/application.yml        MODIFY: POSTGRES_PASSWORD no default; app.ledger.mutator; management
  src/main/java/com/walletledger/
    security/SecurityConfig.java            MODIFY: GET /actuator/health is public
    notification/Notification.java          MODIFY: map created_at
    notification/NotificationRepository.java MODIFY: findByUserIdOrderByIdDesc
    notification/NotificationView.java      NEW
    notification/NotificationService.java   NEW
    notification/NotificationController.java NEW: GET /api/v1/notifications
    outbox/KafkaEventPublisher.java         MODIFY: wait for the broker ack (bug fix, see Task 2)
    ledger/PessimisticBalanceMutator.java   MODIFY: selected unless app.ledger.mutator says otherwise
    ledger/SynchronizedBalanceMutator.java  NEW: JVM-local lock, the control experiment
  src/test/java/com/walletledger/
    HealthEndpointIT.java                   NEW
    notification/NotificationEndpointIT.java NEW
    outbox/KafkaEventPublisherTest.java     NEW (unit)
    ledger/SelectableBalanceMutator.java    MODIFY: fifth strategy
    ledger/AbstractConcurrencyContract.java MODIFY: build the fifth strategy
    ledger/SynchronizedConcurrencyIT.java   NEW
    multiinstance/StackClient.java          NEW: HTTP/JDBC/Kafka helpers against a live stack
    multiinstance/MultiInstanceIT.java      NEW: @Tag("multi-instance")
    multiinstance/SynchronizedControlIT.java NEW: @Tag("multi-instance")
frontend/
  package.json, package-lock.json, index.html, tsconfig.json, vite.config.ts, .dockerignore, Dockerfile
  nginx/templates/00-http.conf.template     http-level: rate-limit zones, resolver, upstream, debug map
  nginx/templates/default.conf.template     HTTP server (local, CI)
  nginx/tls.conf.template                   HTTP→HTTPS + TLS server (mounted over default in prod)
  nginx/snippets/app.conf                   shared server body: static, /api proxy, healthz
  nginx/snippets/proxy.conf                 proxy settings for /api
  nginx/snippets/security-headers.conf      every security header, included wherever add_header is used
  src/main.tsx, App.tsx, index.css, setupTests.ts
  src/api/client.ts                         fetch wrapper: in-memory access token, single-flight refresh
  src/api/idempotency.ts                    key tracker bound to the submitted payload
  src/api/types.ts
  src/components/MoneyForm.tsx
  src/pages/AuthPage.tsx, WalletPage.tsx, TransferPage.tsx, StatementPage.tsx, NotificationsPage.tsx
  src/api/client.test.ts, src/api/idempotency.test.ts, src/components/MoneyForm.test.tsx
docker-compose.yml                          MODIFY: api + web, healthchecks, 127.0.0.1 binds, project name
docker-compose.prod.yml                     NEW: replicas 3, 443, TLS template, certbot
.env.example                                MODIFY
.github/workflows/ci.yml                    NEW
scripts/smoke.sh                            NEW
scripts/multi-instance.sh                   NEW
scripts/backup-db.sh                        NEW
docs/DEPLOY.md                              NEW: Oracle VM runbook (Vietnamese)
README.md                                   MODIFY: CI badge, how to run, live URL
```

No router library: the SPA has five screens and no deep links worth sharing, so a `useState` tab switch replaces `react-router` (which is at a new major, 8.x, with an API not verified here). No axios, no react-query: `fetch` plus ~60 lines covers what SlangWord used axios interceptors for.

---

### Task 1: Health endpoint, notifications read API, and no default DB password

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`, `backend/src/main/java/com/walletledger/security/SecurityConfig.java`
- Modify: `backend/src/main/java/com/walletledger/notification/Notification.java`, `NotificationRepository.java`
- Create: `notification/NotificationView.java`, `NotificationService.java`, `NotificationController.java`
- Test: `backend/src/test/java/com/walletledger/HealthEndpointIT.java`, `backend/src/test/java/com/walletledger/notification/NotificationEndpointIT.java`

**Interfaces:**
- Produces: `GET /actuator/health` (public, `{"status":"UP"}`), used by the compose healthcheck (Task 8). `GET /api/v1/notifications?page=&size=` → Spring `Page` of `NotificationView(String type, String message, Instant createdAt)`, newest first, caller's own only. Used by the SPA (Task 6).

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/com/walletledger/HealthEndpointIT.java
package com.walletledger;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The compose healthcheck calls this without a token, so it must be public. Everything else under
 * /actuator must stay closed: env, beans and configprops print configuration a money service
 * should never show a stranger.
 */
class HealthEndpointIT extends AbstractIntegrationTest {

    @Test
    void healthIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherActuatorEndpointsNeedAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }
}
```

```java
// backend/src/test/java/com/walletledger/notification/NotificationEndpointIT.java
package com.walletledger.notification;

import com.walletledger.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationEndpointIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notifications;

    @Test
    void aUserSeesOnlyTheirOwnNotificationsNewestFirst() throws Exception {
        String alice = "notif-a-" + UUID.randomUUID();
        String bob = "notif-b-" + UUID.randomUUID();
        // Real user ids from the register response: notification.user_id is a foreign key to
        // app_user, and an invented id would fail the insert (the Phase 2B plan's third bug).
        long aliceId = registeredId(alice);
        long bobId = registeredId(bob);
        notifications.save(new Notification(aliceId, "TRANSACTION_POSTED", "DEPOSIT of 1.0000", UUID.randomUUID()));
        notifications.save(new Notification(aliceId, "TRANSACTION_POSTED", "DEPOSIT of 2.0000", UUID.randomUUID()));
        notifications.save(new Notification(bobId, "TRANSACTION_POSTED", "DEPOSIT of 9.0000", UUID.randomUUID()));

        String token = loginTokens(alice).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].message").value("DEPOSIT of 2.0000"))
                .andExpect(jsonPath("$.content[1].message").value("DEPOSIT of 1.0000"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    private long registeredId(String username) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(credentials(username)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("userId").asLong();
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `MVN_IT HealthEndpointIT` then `MVN_IT NotificationEndpointIT`.
Expected: `HealthEndpointIT.healthIsPublicAndUp` fails with status 401 (no actuator yet, and the path is not public — `otherActuatorEndpointsNeedAuthentication` already passes, which is correct: it guards against Step 3 opening too much). `NotificationEndpointIT` compiles (it uses only the existing constructor and `save`) and fails with status 404: the token is valid, so security lets the request through, and no handler is mapped to `/api/v1/notifications` yet.

- [ ] **Step 3: Implement**

`backend/pom.xml`, inside `<dependencies>` after `spring-boot-starter-validation`:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
```

`application.yml` — change the datasource password line, and add `management:` at top level and `ledger:` under `app:` (the latter is used by Task 3; adding it here keeps `application.yml` touched once):

```yaml
    # No default: an empty password must stop startup naming the variable, not be tried.
    password: ${POSTGRES_PASSWORD}
```

```yaml
management:
  endpoints:
    web:
      exposure:
        # Health only. It is the single endpoint the container healthcheck needs.
        include: health
```

```yaml
  ledger:
    # pessimistic (production) | synchronized (MultiInstanceIT's control experiment only)
    mutator: ${APP_LEDGER_MUTATOR:pessimistic}
```

`SecurityConfig.filterChain` — add one matcher before `/api/v1/admin/**`:

```java
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
```

`Notification.java` — add field and getter (same pattern as `LedgerEntry.createdAt`; the column is filled by the database default):

```java
    @Column(name = "created_at", insertable = false, updatable = false)
    private java.time.Instant createdAt;
```

```java
    public java.time.Instant getCreatedAt() {
        return createdAt;
    }
```

`NotificationRepository.java` — add (imports `org.springframework.data.domain.Page`, `org.springframework.data.domain.Pageable`):

```java
    Page<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
```

```java
// backend/src/main/java/com/walletledger/notification/NotificationView.java
package com.walletledger.notification;

import java.time.Instant;

public record NotificationView(String type, String message, Instant createdAt) {

    static NotificationView of(Notification notification) {
        return new NotificationView(notification.getType(), notification.getMessage(),
                notification.getCreatedAt());
    }
}
```

```java
// backend/src/main/java/com/walletledger/notification/NotificationService.java
package com.walletledger.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public Page<NotificationView> forUser(long userId, Pageable pageable) {
        return notifications.findByUserIdOrderByIdDesc(userId, pageable).map(NotificationView::of);
    }
}
```

```java
// backend/src/main/java/com/walletledger/notification/NotificationController.java
package com.walletledger.notification;

import com.walletledger.auth.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Like WalletController: no user id parameter exists, so there is nothing to tamper with. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Page<NotificationView> get(@AuthenticationPrincipal AuthenticatedUser user, Pageable pageable) {
        return notifications.forUser(user.id(), pageable);
    }
}
```

- [ ] **Step 4: Run and confirm green**

Run: `MVN_IT HealthEndpointIT`, then `MVN_IT NotificationEndpointIT`, then `MVN_IT ProblemDetailIT` (the security chain changed).
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/main/java/com/walletledger/security/SecurityConfig.java backend/src/main/java/com/walletledger/notification backend/src/test/java/com/walletledger/HealthEndpointIT.java backend/src/test/java/com/walletledger/notification/NotificationEndpointIT.java
git commit -m "feat: add a public health check and the notifications read endpoint" -m "Actuator exposes health only; it is what the compose healthcheck calls, so it is the one public path outside /api/v1/auth. GET /api/v1/notifications returns the caller's own notifications newest first, with no user id parameter to tamper with. POSTGRES_PASSWORD loses its empty default so a missing value stops startup by name."
```

---

### Task 2: `KafkaEventPublisher` waits for the broker (bug found while planning)

**Why this is in Phase 3:** `KafkaEventPublisher.publish` calls `kafka.send(...)` and returns without waiting. `KafkaTemplate.send` is asynchronous, so a broker that rejects or never acknowledges the record still lets `OutboxRelay` call `markPublished()` — the event is lost silently, which is at-most-once, not the at-least-once spec §7 promises. `MultiInstanceIT` (Task 9) asserts every event appears on the topic exactly once, so this has to hold first.

**Files:**
- Modify: `backend/src/main/java/com/walletledger/outbox/KafkaEventPublisher.java`
- Test: `backend/src/test/java/com/walletledger/outbox/KafkaEventPublisherTest.java` (unit, Surefire)

- [ ] **Step 1: Write the failing unit test**

```java
// backend/src/test/java/com/walletledger/outbox/KafkaEventPublisherTest.java
package com.walletledger.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    /**
     * OutboxRelay marks an event published only if publish() returns normally. If publish()
     * returned before the broker answered, a rejected record would still be marked published and
     * never retried.
     */
    @Test
    void aRejectedSendFailsThePublish() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafka);
        OutboxEvent event = new OutboxEvent("LedgerTransaction", 7L, OutboxEvent.TRANSACTION_POSTED, "{}");

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("broker down");
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `MVN_UNIT KafkaEventPublisherTest`
Expected: FAIL — "Expecting code to raise a throwable" (the failed future is ignored).

- [ ] **Step 3: Implement**

Replace `publish` in `KafkaEventPublisher.java` (add imports `java.util.concurrent.ExecutionException`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.TimeoutException`):

```java
    private static final long ACK_TIMEOUT_SECONDS = 10;

    @Override
    public void publish(OutboxEvent event) {
        // Keyed by aggregate id: Kafka guarantees ordering within a partition, so every event
        // for the same transaction lands in the same partition in the order it was posted.
        // send() is asynchronous. Waiting for the acknowledgement is what makes the relay's
        // markPublished() true: without it a rejected record is marked published and lost.
        try {
            kafka.send(TOPIC, event.getAggregateId().toString(), event.getPayload())
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing outbox event " + event.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge outbox event " + event.getId(), e);
        }
    }
```

- [ ] **Step 4: Run and confirm green, including the real-broker tests**

Run: `MVN_UNIT KafkaEventPublisherTest`, then `MVN_IT KafkaEventPublisherIT`, then `MVN_IT EndToEndEventIT`.
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/walletledger/outbox/KafkaEventPublisher.java backend/src/test/java/com/walletledger/outbox/KafkaEventPublisherTest.java
git commit -m "fix: wait for the Kafka acknowledgement before marking an event published" -m "KafkaTemplate.send is asynchronous. publish() returned before the broker answered, so OutboxRelay marked a rejected record published and never retried it: at-most-once delivery where spec section 7 promises at-least-once. publish() now waits up to 10 seconds for the ack and throws otherwise, which OutboxRelay already turns into a retry on the next tick."
```

---

### Task 3: `SynchronizedBalanceMutator` — the control experiment's strategy

**Files:**
- Create: `backend/src/main/java/com/walletledger/ledger/SynchronizedBalanceMutator.java`
- Modify: `backend/src/main/java/com/walletledger/ledger/PessimisticBalanceMutator.java`
- Modify: `backend/src/test/java/com/walletledger/ledger/SelectableBalanceMutator.java`, `AbstractConcurrencyContract.java`
- Test: `backend/src/test/java/com/walletledger/ledger/SynchronizedConcurrencyIT.java`

**Interfaces:**
- Consumes: `app.ledger.mutator` (Task 1 added it to `application.yml`).
- Produces: `APP_LEDGER_MUTATOR=synchronized` swaps the production strategy (used only by `scripts/multi-instance.sh`, Task 9). `SelectableBalanceMutator.synchronizedLock()` for the shared contract.

The fifth strategy joins the existing shared contract instead of getting its own test context: bug #14 is what happens when a sixth Hikari pool appears.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/walletledger/ledger/SynchronizedConcurrencyIT.java
package com.walletledger.ledger;

/**
 * Inside one JVM a Java lock held until commit is as correct as FOR UPDATE: the shared contract
 * must pass unchanged. SynchronizedControlIT (multi-instance) then shows the same strategy failing
 * once three JVMs share the database, which is the whole point of having it.
 */
class SynchronizedConcurrencyIT extends AbstractConcurrencyContract {

    @Override
    protected BalanceMutator strategyUnderTest() {
        return selectable.synchronizedLock();
    }
}
```

- [ ] **Step 2: Run and confirm it fails**

Run: `MVN_IT SynchronizedConcurrencyIT`
Expected: compile error — `synchronizedLock()` does not exist.

- [ ] **Step 3: Implement**

```java
// backend/src/main/java/com/walletledger/ledger/SynchronizedBalanceMutator.java
package com.walletledger.ledger;

import com.walletledger.account.Account;
import com.walletledger.account.AccountRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The strategy a developer reaches for first, and the reason MultiInstanceIT exists: a Java lock
 * instead of a database lock. It is held from acquire() until the transaction completes, so within
 * one JVM nothing can read a balance another thread is about to change — SynchronizedConcurrencyIT
 * proves that. A second JVM has its own LOCK and sees none of this.
 * <p>
 * Never the production strategy. It is selected only by APP_LEDGER_MUTATOR=synchronized, which
 * scripts/multi-instance.sh sets for SynchronizedControlIT.
 */
// ponytail: one global lock for every account — fine for a control experiment, never for production.
@Component
@Primary
@ConditionalOnProperty(name = "app.ledger.mutator", havingValue = "synchronized")
public class SynchronizedBalanceMutator implements BalanceMutator {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private final AccountRepository accounts;

    public SynchronizedBalanceMutator(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public AccountPair acquire(long firstId, long secondId) {
        LOCK.lock();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOCK.unlock();
            throw new IllegalStateException("SynchronizedBalanceMutator needs an active transaction");
        }
        // Released after commit or rollback, not when acquire() returns: releasing earlier would
        // let the next thread read the balance before this one's update is committed.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                LOCK.unlock();
            }
        });
        return new AccountPair(read(firstId), read(secondId));
    }

    @Override
    public String strategyName() {
        return "synchronized";
    }

    private Account read(long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No such account: " + accountId));
    }
}
```

`PessimisticBalanceMutator.java` — add the import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` and one annotation under `@Primary`:

```java
@ConditionalOnProperty(name = "app.ledger.mutator", havingValue = "pessimistic", matchIfMissing = true)
```

`SelectableBalanceMutator.java` — add the field, constructor parameter and accessor:

```java
    private final SynchronizedBalanceMutator synchronizedLock;
```

```java
    SelectableBalanceMutator(AccountRepository accounts, PessimisticBalanceMutator pessimistic,
                             OptimisticBalanceMutator optimistic, SerializableBalanceMutator serializable,
                             SynchronizedBalanceMutator synchronizedLock) {
        this.pessimistic = pessimistic;
        this.optimistic = optimistic;
        this.serializable = serializable;
        this.synchronizedLock = synchronizedLock;
        this.unsafe = new UnsafeBalanceMutator(accounts);
        this.active = pessimistic;
    }
```

```java
    BalanceMutator synchronizedLock() {
        return synchronizedLock;
    }
```

`AbstractConcurrencyContract.SelectableStrategy.selectableBalanceMutator` — pass the fifth instance:

```java
            return new SelectableBalanceMutator(accounts,
                    new PessimisticBalanceMutator(accounts),
                    new OptimisticBalanceMutator(accounts),
                    new SerializableBalanceMutator(accounts),
                    new SynchronizedBalanceMutator(accounts));
```

- [ ] **Step 4: Run and confirm green**

Run: `MVN_IT SynchronizedConcurrencyIT`, then `MVN_IT PessimisticConcurrencyIT` (the selectable bean changed).
Expected: green; the printed line reads `synchronized withdrawals: successes=100 ... cached=0.0000 derived=0.0000`.

- [ ] **Step 5: Run the whole suite**

Run: `MVN_FULL`
Expected: green; test count = 118 + 2 (Task 1 Health) + 1 (Task 1 Notification) + 1 (Task 2 unit) + 2 (this task) = **124**, coverage gate passes. Read the real figures from `backend/target/site/jacoco/jacoco.csv`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/walletledger/ledger/SynchronizedBalanceMutator.java backend/src/main/java/com/walletledger/ledger/PessimisticBalanceMutator.java backend/src/test/java/com/walletledger/ledger/SelectableBalanceMutator.java backend/src/test/java/com/walletledger/ledger/AbstractConcurrencyContract.java backend/src/test/java/com/walletledger/ledger/SynchronizedConcurrencyIT.java
git commit -m "test: add a JVM-local lock strategy as the multi-instance control experiment" -m "SynchronizedBalanceMutator holds a ReentrantLock from acquire() until the transaction completes. Inside one JVM it passes the shared concurrency contract exactly like FOR UPDATE. It exists to fail in SynchronizedControlIT once three JVMs share one database, and is selected only by APP_LEDGER_MUTATOR=synchronized."
```

---

### Task 4: Backend image

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`

- [ ] **Step 1: Write the files**

```dockerfile
# backend/Dockerfile
# The build stage runs on the builder's own architecture ($BUILDPLATFORM): a jar is
# architecture-independent, so building it under QEMU for arm64 would only be slower.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/wallet-ledger-0.1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8091
# MaxRAMPercentage instead of a fixed -Xmx: the heap follows the container's memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

```
# backend/.dockerignore
target
```

- [ ] **Step 2: Build it**

Run (repo root): `docker build -t wallet-ledger-api:local backend`
Expected: success.

- [ ] **Step 3: Prove it runs as non-root and fails fast without secrets**

Run: `docker run --rm --entrypoint id wallet-ledger-api:local` → expected `uid=` not `0`.
Run: `docker run --rm wallet-ledger-api:local 2>&1 | grep -E "APP_JWT_SECRET|POSTGRES_PASSWORD" | head -3`
Expected: a startup failure line naming `POSTGRES_PASSWORD` (placeholder cannot be resolved) or `APP_JWT_SECRET`. Record which one appears first in the report — whichever Spring resolves first — and that the process exits non-zero.

- [ ] **Step 4: Commit**

```bash
git add backend/Dockerfile backend/.dockerignore
git commit -m "build: add a multi-stage, non-root API image" -m "The Maven stage runs on the build platform because a jar is architecture-independent; only the JRE stage is per-architecture, which keeps the arm64 build out of QEMU. The runtime user is unprivileged and the heap follows the container memory limit."
```

---

### Task 5: Frontend scaffold, API client and idempotency keys

**Files:**
- Create: `frontend/package.json`, `frontend/index.html`, `frontend/tsconfig.json`, `frontend/vite.config.ts`, `frontend/src/main.tsx`, `frontend/src/index.css`, `frontend/src/setupTests.ts`, `frontend/src/api/client.ts`, `frontend/src/api/idempotency.ts`, `frontend/src/api/types.ts`, `frontend/src/App.tsx` (placeholder render only; Task 6 fills it)
- Test: `frontend/src/api/client.test.ts`, `frontend/src/api/idempotency.test.ts`

**Interfaces:**
- Produces: `api<T>(path, { method?, body?, idempotencyKey? }): Promise<T>` (path relative to `/api/v1`), `ApiError { status, message }`, `storeSession(session, username?)`, `clearSession()`, `storedUsername()`, `logout()`, `setSessionEndedHandler(fn)`; `createKeyTracker(): { keyFor(payload: string): string; succeeded(): void }`, `useIdempotencyKey()`, `newKey()`; types `WalletView`, `TransactionView`, `StatementEntry`, `NotificationView`, `Page<T>`, `Session`.

Decisions (argued in the report, and later in docs): the access token lives in a module variable (never storage), the refresh token in `sessionStorage` — a reload keeps you signed in, a closed tab does not, and localStorage (SlangWord) would hand both tokens to any XSS for a week. A reload therefore has no access token: the first request 401s, the single-flight refresh runs, and the request is replayed. The single flight matters twice here: a busy page, and React `StrictMode` running effects twice in development — both would otherwise send the same rotating refresh token twice and trip reuse detection.

The idempotency key is bound to the **payload**: the same submission retried reuses its key (a timeout retry cannot pay twice), an edited submission gets a new one (reusing it would earn a `422 IDEMPOTENCY_KEY_REUSED`), and success clears it. `crypto.randomUUID()` is not used: it only exists in secure contexts, and the site is served over plain HTTP locally and in CI.

- [ ] **Step 1: Write the project files**

```json
{
  "name": "wallet-ledger-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "engines": { "node": ">=22.22.0" },
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "lint": "oxlint src",
    "test": "vitest",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^19.3.0",
    "react-dom": "^19.3.0"
  },
  "devDependencies": {
    "@tailwindcss/vite": "^4.3.3",
    "@testing-library/dom": "^10.4.2",
    "@testing-library/jest-dom": "^7.0.1",
    "@testing-library/react": "^16.3.3",
    "@types/react": "^19.3.0",
    "@types/react-dom": "^19.3.0",
    "@vitejs/plugin-react": "^6.1.1",
    "jsdom": "^30.1.1",
    "oxlint": "^1.85.0",
    "tailwindcss": "^4.3.3",
    "typescript": "~6.0.3",
    "vite": "^8.3.0",
    "vitest": "^5.0.1"
  }
}
```

TypeScript is pinned to 6.0, not the 7.0 on npm: 7.0 is the native (Go) compiler rewrite, and nothing here needs to be its first user.

```html
<!-- frontend/index.html -->
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Wallet Ledger</title>
  </head>
  <body class="bg-slate-50 text-slate-900">
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "isolatedModules": true,
    "skipLibCheck": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "types": ["vite/client", "vitest/globals"]
  },
  "include": ["src", "vite.config.ts"]
}
```

```ts
// frontend/vite.config.ts
/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Dev only: forward /api to the compose nginx, which forwards to the API replicas.
    proxy: { '/api': 'http://localhost:8095' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
    css: false,
  },
})
```

```css
/* frontend/src/index.css */
@import "tailwindcss";
```

```ts
// frontend/src/setupTests.ts
import '@testing-library/jest-dom/vitest'
```

```tsx
// frontend/src/main.tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

```tsx
// frontend/src/App.tsx  (replaced in Task 6)
export default function App() {
  return <main className="p-6">Wallet Ledger</main>
}
```

```ts
// frontend/src/api/types.ts
/** Amounts are strings on the wire and stay strings here: a JS number would round NUMERIC(19,4). */
export type Session = { accessToken: string; refreshToken: string }
export type WalletView = { accountId: number; balance: string }
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER' | 'REVERSAL'
export type TransactionView = {
  transactionId: string
  type: TransactionType
  amount: string
  balanceAfter: string
}
export type StatementEntry = {
  transactionId: string
  type: TransactionType
  amount: string
  description: string | null
  createdAt: string
}
export type NotificationView = { type: string; message: string; createdAt: string }
export type Page<T> = { content: T[]; totalPages: number; number: number }
```

- [ ] **Step 2: Install**

Run (from `frontend/`, cache on D: because C: is full): `npm install --cache D:/tmp-npm-cache`
Expected: `package-lock.json` created, no `ERESOLVE` and no unmet-peer warnings. If a peer warning names a package, fix the version in `package.json` from `npm view <pkg> peerDependencies` — do not add `--legacy-peer-deps`.

- [ ] **Step 3: Write the failing tests**

```ts
// frontend/src/api/idempotency.test.ts
import { createKeyTracker, newKey } from './idempotency'

test('a retry of the same submission reuses its key', () => {
  const keys = createKeyTracker()
  const first = keys.keyFor('{"amount":"5"}')
  expect(keys.keyFor('{"amount":"5"}')).toBe(first)
})

test('an edited submission gets a new key', () => {
  const keys = createKeyTracker()
  const first = keys.keyFor('{"amount":"5"}')
  expect(keys.keyFor('{"amount":"6"}')).not.toBe(first)
})

test('success clears the key, so the next identical submission is a new operation', () => {
  const keys = createKeyTracker()
  const first = keys.keyFor('{"amount":"5"}')
  keys.succeeded()
  expect(keys.keyFor('{"amount":"5"}')).not.toBe(first)
})

test('keys are version 4 UUIDs', () => {
  expect(newKey()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})
```

```ts
// frontend/src/api/client.test.ts
import { api, ApiError, clearSession, setSessionEndedHandler, storeSession } from './client'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

test('two requests that both find the access token expired share one refresh', async () => {
  storeSession({ accessToken: 'old', refreshToken: 'r1' }, 'alice')
  let refreshes = 0
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/auth/refresh')) {
      refreshes++
      return json(200, { accessToken: 'new', refreshToken: 'r2' })
    }
    const auth = new Headers(init?.headers).get('Authorization')
    return auth === 'Bearer new' ? json(200, { ok: true }) : json(401, { detail: 'expired' })
  }))

  await Promise.all([api('/wallet'), api('/statement')])

  expect(refreshes).toBe(1)
})

test('a failed refresh ends the session', async () => {
  storeSession({ accessToken: 'old', refreshToken: 'reused' }, 'alice')
  const ended = vi.fn()
  setSessionEndedHandler(ended)
  vi.stubGlobal('fetch', vi.fn(async () => json(401, { detail: 'expired' })))

  await expect(api('/wallet')).rejects.toBeInstanceOf(ApiError)
  expect(ended).toHaveBeenCalledOnce()
})

test('the problem detail becomes the error message', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => json(409, { title: 'Insufficient funds', detail: 'Not enough' })))

  await expect(api('/wallet/withdrawals', { method: 'POST' })).rejects.toMatchObject({ status: 409, message: 'Not enough' })
})
```

- [ ] **Step 4: Run and confirm they fail**

Run (from `frontend/`): `npx vitest --run`
Expected: both files fail to import (`./idempotency`, `./client` do not exist).

- [ ] **Step 5: Implement**

```ts
// frontend/src/api/idempotency.ts
import { useState } from 'react'

/** A v4 UUID from getRandomValues: crypto.randomUUID() is missing outside secure (HTTPS) contexts. */
export function newKey(): string {
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

/**
 * One key per submission, where "the same submission" means the same payload. A retry after a
 * timeout resends the key, so the server replays instead of paying twice; an edited form gets a
 * new key, because the server rejects a key reused for a different body (422).
 */
export function createKeyTracker() {
  let last: { payload: string; key: string } | null = null
  return {
    keyFor(payload: string): string {
      if (last?.payload !== payload) last = { payload, key: newKey() }
      return last.key
    },
    succeeded() {
      last = null
    },
  }
}

export function useIdempotencyKey() {
  return useState(createKeyTracker)[0]
}
```

```ts
// frontend/src/api/client.ts
import type { Session } from './types'

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
  }
}

const REFRESH_KEY = 'wallet.refresh'
const USER_KEY = 'wallet.user'

// In memory only: storage would hand the token to any script that runs on the page.
let accessToken: string | null = null
let refreshInFlight: Promise<void> | null = null
let onSessionEnded: () => void = () => {}

export function setSessionEndedHandler(handler: () => void) {
  onSessionEnded = handler
}

export function storeSession(session: Session, username?: string) {
  accessToken = session.accessToken
  sessionStorage.setItem(REFRESH_KEY, session.refreshToken)
  if (username) sessionStorage.setItem(USER_KEY, username)
}

export function storedUsername(): string | null {
  return sessionStorage.getItem(REFRESH_KEY) ? sessionStorage.getItem(USER_KEY) : null
}

export function clearSession() {
  accessToken = null
  sessionStorage.removeItem(REFRESH_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export async function logout() {
  const refreshToken = sessionStorage.getItem(REFRESH_KEY)
  clearSession()
  if (refreshToken) {
    // Revoke server-side, but never block sign-out on the network.
    await postJson('/auth/logout', { refreshToken }).catch(() => undefined)
  }
}

function postJson(path: string, body: unknown) {
  return fetch(`/api/v1${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function refresh() {
  const refreshToken = sessionStorage.getItem(REFRESH_KEY)
  if (!refreshToken) throw new ApiError(401, 'Not signed in')
  const res = await postJson('/auth/refresh', { refreshToken })
  if (!res.ok) throw new ApiError(res.status, 'Session expired')
  storeSession(await res.json())
}

/**
 * Shared by every caller that hits a 401 at the same moment. The server rotates refresh tokens
 * and treats a reused one as theft, so two parallel refreshes would sign the user out.
 */
function refreshOnce(): Promise<void> {
  refreshInFlight ??= refresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

type Options = { method?: string; body?: string; idempotencyKey?: string }

export async function api<T>(path: string, { method = 'GET', body, idempotencyKey }: Options = {}): Promise<T> {
  const send = () => {
    const headers = new Headers({ 'Content-Type': 'application/json' })
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    if (idempotencyKey) headers.set('Idempotency-Key', idempotencyKey)
    return fetch(`/api/v1${path}`, { method, headers, body })
  }

  let res = await send()
  if (res.status === 401 && !path.startsWith('/auth/')) {
    try {
      await refreshOnce()
    } catch {
      clearSession()
      onSessionEnded()
      throw new ApiError(401, 'Your session has ended. Please sign in again.')
    }
    res = await send()
  }
  if (!res.ok) throw new ApiError(res.status, await problemMessage(res))
  return (res.status === 204 ? undefined : await res.json()) as T
}

async function problemMessage(res: Response): Promise<string> {
  try {
    const problem = await res.json()
    return problem.detail ?? problem.title ?? `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}
```

- [ ] **Step 6: Run and confirm green; lint and type-check**

Run (from `frontend/`): `npx vitest --run` → 7 passed. `npm run lint` → 0 errors. `npx tsc --noEmit` → no output.

- [ ] **Step 7: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/index.html frontend/tsconfig.json frontend/vite.config.ts frontend/src
git commit -m "feat: scaffold the React 19 SPA with a single-flight refresh and payload-bound idempotency keys" -m "The access token stays in memory and the refresh token in sessionStorage. Concurrent 401s share one refresh, because the server rotates refresh tokens and treats a reused one as theft. An Idempotency-Key is tied to the submitted payload: a retry resends it, an edit replaces it, success clears it. Keys come from getRandomValues because crypto.randomUUID only exists over HTTPS."
```

---

### Task 6: Screens — sign-in, wallet, transfer, statement with refund, notifications

**Files:**
- Create: `frontend/src/components/MoneyForm.tsx`, `frontend/src/pages/AuthPage.tsx`, `WalletPage.tsx`, `TransferPage.tsx`, `StatementPage.tsx`, `NotificationsPage.tsx`
- Modify: `frontend/src/App.tsx`
- Test: `frontend/src/components/MoneyForm.test.tsx`

**Interfaces:**
- Consumes: everything Task 5 produced; endpoints `POST /auth/register|login`, `GET /wallet`, `POST /wallet/deposits|withdrawals`, `POST /transfers`, `GET /statement`, `POST /transactions/{id}/refund`, `GET /notifications` (Task 1).
- Produces: `MoneyForm({ label, path, withRecipient?, onDone })`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/components/MoneyForm.test.tsx
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MoneyForm } from './MoneyForm'

afterEach(() => vi.unstubAllGlobals())

test('a retry after a failure resends the same Idempotency-Key', async () => {
  const keys: (string | null)[] = []
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
    keys.push(new Headers(init?.headers).get('Idempotency-Key'))
    return keys.length === 1
      ? new Response(JSON.stringify({ detail: 'Gateway timeout' }), { status: 504 })
      : new Response(JSON.stringify({ transactionId: 't1', type: 'DEPOSIT', amount: '5.0000', balanceAfter: '5.0000' }), { status: 201 })
  }))
  const onDone = vi.fn()
  render(<MoneyForm label="Deposit" path="/wallet/deposits" onDone={onDone} />)

  fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '5' } })
  fireEvent.click(screen.getByRole('button', { name: 'Deposit' }))
  await screen.findByText('Gateway timeout')
  fireEvent.click(screen.getByRole('button', { name: 'Deposit' }))

  await waitFor(() => expect(onDone).toHaveBeenCalledOnce())
  expect(keys).toHaveLength(2)
  expect(keys[0]).not.toBeNull()
  expect(keys[1]).toBe(keys[0])
})
```

- [ ] **Step 2: Run and confirm it fails**

Run: `npx vitest --run src/components` → fails to import `./MoneyForm`.

- [ ] **Step 3: Implement**

```tsx
// frontend/src/components/MoneyForm.tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import { api } from '../api/client'
import { useIdempotencyKey } from '../api/idempotency'
import type { TransactionView } from '../api/types'

type Props = {
  label: string
  path: string
  withRecipient?: boolean
  onDone: (tx: TransactionView) => void
}

export const inputClass = 'mt-1 block w-full rounded border border-slate-300 px-3 py-2'
export const buttonClass = 'rounded bg-slate-900 px-4 py-2 text-white disabled:opacity-50'

export function MoneyForm({ label, path, withRecipient = false, onDone }: Props) {
  const keys = useIdempotencyKey()
  const [toUsername, setToUsername] = useState('')
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    // The amount goes as the string the user typed; the server rejects more than 4 decimals.
    const body = JSON.stringify(withRecipient ? { toUsername, amount, description } : { amount, description })
    try {
      const tx = await api<TransactionView>(path, { method: 'POST', body, idempotencyKey: keys.keyFor(body) })
      keys.succeeded()
      setToUsername('')
      setAmount('')
      setDescription('')
      onDone(tx)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-3 rounded border border-slate-200 bg-white p-4">
      <h2 className="font-semibold">{label}</h2>
      {withRecipient && (
        <label className="block text-sm">
          Recipient username
          <input className={inputClass} value={toUsername} onChange={(e) => setToUsername(e.target.value)} required />
        </label>
      )}
      <label className="block text-sm">
        Amount
        <input className={inputClass} inputMode="decimal" pattern="\d+(\.\d{1,4})?" value={amount}
          onChange={(e) => setAmount(e.target.value)} required />
      </label>
      <label className="block text-sm">
        Description
        <input className={inputClass} maxLength={255} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <button type="submit" className={buttonClass} disabled={busy}>{label}</button>
    </form>
  )
}
```

```tsx
// frontend/src/pages/AuthPage.tsx
import { useState } from 'react'
import type { FormEvent } from 'react'
import { api, storeSession } from '../api/client'
import type { Session } from '../api/types'
import { buttonClass, inputClass } from '../components/MoneyForm'

export function AuthPage({ onSignedIn }: { onSignedIn: (username: string) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const body = JSON.stringify({ username, password })
    try {
      if (mode === 'register') await api('/auth/register', { method: 'POST', body })
      storeSession(await api<Session>('/auth/login', { method: 'POST', body }), username)
      onSignedIn(username)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    }
  }

  return (
    <main className="mx-auto mt-16 max-w-sm">
      <form onSubmit={submit} className="space-y-3 rounded border border-slate-200 bg-white p-6">
        <h1 className="text-xl font-semibold">{mode === 'login' ? 'Sign in' : 'Create account'}</h1>
        <label className="block text-sm">
          Username
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </label>
        <label className="block text-sm">
          Password
          <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={mode === 'register' ? 8 : undefined} required />
        </label>
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        <button type="submit" className={buttonClass}>{mode === 'login' ? 'Sign in' : 'Register'}</button>
        <button type="button" className="ml-3 text-sm underline" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
          {mode === 'login' ? 'Need an account?' : 'Have an account?'}
        </button>
      </form>
    </main>
  )
}
```

```tsx
// frontend/src/pages/WalletPage.tsx
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { WalletView } from '../api/types'
import { MoneyForm } from '../components/MoneyForm'

export function WalletPage() {
  const [wallet, setWallet] = useState<WalletView | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<WalletView>('/wallet').then(setWallet, (e: Error) => setError(e.message))
  }, [])

  const onDone = (tx: { balanceAfter: string }) => setWallet((w) => (w ? { ...w, balance: tx.balanceAfter } : w))

  return (
    <section className="space-y-4">
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {wallet && (
        <div className="rounded border border-slate-200 bg-white p-4">
          <p className="text-sm text-slate-500">Account #{wallet.accountId}</p>
          <p className="text-3xl font-semibold tabular-nums">{wallet.balance}</p>
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-2">
        <MoneyForm label="Deposit" path="/wallet/deposits" onDone={onDone} />
        <MoneyForm label="Withdraw" path="/wallet/withdrawals" onDone={onDone} />
      </div>
    </section>
  )
}
```

```tsx
// frontend/src/pages/TransferPage.tsx
import { useState } from 'react'
import type { TransactionView } from '../api/types'
import { MoneyForm } from '../components/MoneyForm'

export function TransferPage() {
  const [last, setLast] = useState<TransactionView | null>(null)
  return (
    <section className="max-w-md space-y-4">
      <MoneyForm label="Transfer" path="/transfers" withRecipient onDone={setLast} />
      {last && (
        <p className="text-sm">
          Sent {last.amount}. Balance now <span className="tabular-nums">{last.balanceAfter}</span>.
        </p>
      )}
    </section>
  )
}
```

```tsx
// frontend/src/pages/StatementPage.tsx
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { useIdempotencyKey } from '../api/idempotency'
import type { Page, StatementEntry, TransactionView } from '../api/types'
import { inputClass } from '../components/MoneyForm'

/** A yyyy-mm-dd picked in the user's time zone, as the instant that local day starts. */
function startOfLocalDay(date: string, plusDays = 0): string {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y, m - 1, d + plusDays).toISOString()
}

export function StatementPage() {
  const refundKeys = useIdempotencyKey()
  const [page, setPage] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [type, setType] = useState('')
  const [data, setData] = useState<Page<StatementEntry> | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = useCallback(() => {
    const query = new URLSearchParams({ page: String(page), size: '10' })
    if (from) query.set('from', startOfLocalDay(from))
    if (to) query.set('to', startOfLocalDay(to, 1)) // the server's "to" is exclusive
    if (type) query.set('type', type)
    api<Page<StatementEntry>>(`/statement?${query}`).then(setData, (e: Error) => setMessage(e.message))
  }, [page, from, to, type])

  useEffect(load, [load])

  async function refund(transactionId: string) {
    setMessage(null)
    try {
      const tx = await api<TransactionView>(`/transactions/${transactionId}/refund`, {
        method: 'POST',
        idempotencyKey: refundKeys.keyFor(transactionId),
      })
      refundKeys.succeeded()
      setMessage(`Refunded. Balance now ${tx.balanceAfter}.`)
      load()
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Refund failed')
    }
  }

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="text-sm">From<input type="date" className={inputClass} value={from} onChange={(e) => { setPage(0); setFrom(e.target.value) }} /></label>
        <label className="text-sm">To<input type="date" className={inputClass} value={to} onChange={(e) => { setPage(0); setTo(e.target.value) }} /></label>
        <label className="text-sm">Type
          <select className={inputClass} value={type} onChange={(e) => { setPage(0); setType(e.target.value) }}>
            <option value="">All</option>
            <option>DEPOSIT</option><option>WITHDRAWAL</option><option>TRANSFER</option><option>REVERSAL</option>
          </select>
        </label>
      </div>
      {message && <p role="status" className="text-sm">{message}</p>}
      <table className="w-full border-collapse bg-white text-sm">
        <thead><tr className="border-b text-left"><th className="p-2">When</th><th>Type</th><th>Description</th><th className="text-right">Amount</th><th /></tr></thead>
        <tbody>
          {data?.content.map((row) => (
            <tr key={`${row.transactionId}-${row.amount}`} className="border-b">
              <td className="p-2">{new Date(row.createdAt).toLocaleString()}</td>
              <td>{row.type}</td>
              <td>{row.description}</td>
              <td className="text-right tabular-nums">{row.amount}</td>
              <td className="text-right">
                {/* The server decides who may refund (initiator only); the UI does not guess. */}
                {row.type !== 'REVERSAL' && (
                  <button className="px-2 underline" onClick={() => refund(row.transactionId)}>Refund</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {data && data.totalPages > 1 && (
        <div className="flex items-center gap-3">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="text-sm">Page {data.number + 1} of {data.totalPages}</span>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </section>
  )
}
```

```tsx
// frontend/src/pages/NotificationsPage.tsx
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { NotificationView, Page } from '../api/types'

export function NotificationsPage() {
  const [items, setItems] = useState<NotificationView[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Page<NotificationView>>('/notifications?size=50').then((p) => setItems(p.content), (e: Error) => setError(e.message))
  }, [])

  return (
    <section>
      {error && <p role="alert" className="text-red-700">{error}</p>}
      {items.length === 0 && !error && <p className="text-sm text-slate-500">No notifications yet. They arrive a few seconds after a transaction, through Kafka.</p>}
      <ul className="divide-y rounded border border-slate-200 bg-white">
        {items.map((n, i) => (
          <li key={i} className="p-3 text-sm">
            <span className="tabular-nums">{n.message}</span>
            <span className="ml-3 text-slate-500">{new Date(n.createdAt).toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
```

```tsx
// frontend/src/App.tsx
import { useEffect, useState } from 'react'
import { logout, setSessionEndedHandler, storedUsername } from './api/client'
import { AuthPage } from './pages/AuthPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { StatementPage } from './pages/StatementPage'
import { TransferPage } from './pages/TransferPage'
import { WalletPage } from './pages/WalletPage'

const tabs = { Wallet: WalletPage, Transfer: TransferPage, Statement: StatementPage, Notifications: NotificationsPage }
type Tab = keyof typeof tabs

export default function App() {
  const [user, setUser] = useState(storedUsername)
  const [tab, setTab] = useState<Tab>('Wallet')

  useEffect(() => setSessionEndedHandler(() => setUser(null)), [])

  if (!user) return <AuthPage onSignedIn={setUser} />

  const Current = tabs[tab]
  return (
    <div className="mx-auto max-w-4xl p-6">
      <header className="mb-6 flex items-center gap-4">
        <h1 className="font-semibold">Wallet Ledger</h1>
        <nav className="flex gap-2">
          {(Object.keys(tabs) as Tab[]).map((t) => (
            <button key={t} onClick={() => setTab(t)}
              className={t === tab ? 'rounded bg-slate-900 px-3 py-1 text-white' : 'rounded px-3 py-1'}>{t}</button>
          ))}
        </nav>
        <span className="ml-auto text-sm text-slate-500">{user}</span>
        <button className="text-sm underline" onClick={() => { void logout(); setUser(null) }}>Sign out</button>
      </header>
      <Current />
    </div>
  )
}
```

- [ ] **Step 4: Run tests, lint, build**

Run (from `frontend/`): `npx vitest --run` → 8 passed. `npm run lint` → 0 errors. `npm run build` → `dist/` written, no type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat: add the wallet, transfer, statement and notification screens" -m "One MoneyForm drives deposit, withdrawal and transfer, so the idempotency behaviour is written once; its test proves a retry after a 504 resends the same key. The statement filters by local-day date range and type, pages, and offers a refund on every non-reversal row. Who may refund is left to the server."
```

---

### Task 7: nginx and the web image

**Files:**
- Create: `frontend/nginx/templates/00-http.conf.template`, `frontend/nginx/templates/default.conf.template`, `frontend/nginx/tls.conf.template`, `frontend/nginx/snippets/app.conf`, `frontend/nginx/snippets/proxy.conf`, `frontend/nginx/snippets/security-headers.conf`, `frontend/Dockerfile`, `frontend/.dockerignore`

**Interfaces:**
- Consumes (env): `API_PORT` (8091), `NGINX_EXPOSE_UPSTREAM` (`on` only in `scripts/multi-instance.sh`), `SERVER_NAME` (TLS only).
- Produces: container port 8080 (HTTP) and 8443 (TLS, prod only); `GET /healthz` → 200; `X-Upstream` response header on `/api/` only when `NGINX_EXPOSE_UPSTREAM=on`.

Decisions: `nginx-unprivileged` so the web image is non-root like the API. The upstream uses `server api:8091 resolve` (open-source nginx since 1.27.3) so replicas that restart with new IPs are picked up — a plain `proxy_pass http://api:8091` resolves once at startup. `proxy_next_upstream off`: nginx must never replay a POST to a second replica. Every `add_header` block includes the full security-header snippet, because nginx drops inherited `add_header`s from any block that declares its own (SlangWord's lesson). HSTS is in that snippet unconditionally: browsers ignore it over HTTP, so it costs nothing locally and cannot be forgotten in the TLS block. `X-Upstream` would disclose internal addresses, so it is off unless the test harness turns it on.

- [ ] **Step 1: Write the files**

```nginx
# frontend/nginx/templates/00-http.conf.template  (http context; rendered by the image's envsubst)
limit_req_zone $binary_remote_addr zone=auth:10m rate=10r/m;
limit_req_zone $binary_remote_addr zone=api:10m rate=20r/s;
limit_req_status 429;

# Docker's embedded DNS. valid=10s: a replaced or scaled replica is seen within ten seconds.
resolver 127.0.0.11 valid=10s ipv6=off;

upstream api {
    zone api 64k;
    server api:${API_PORT} resolve;
}

# The replica address, only when the multi-instance test harness asks for it.
map "${NGINX_EXPOSE_UPSTREAM}" $upstream_debug {
    on      $upstream_addr;
    default "";
}
```

```nginx
# frontend/nginx/templates/default.conf.template  (plain HTTP: local and CI)
server {
    listen 8080;
    server_name _;
    include /etc/nginx/snippets/app.conf;
}
```

```nginx
# frontend/nginx/tls.conf.template  (mounted over default.conf.template by docker-compose.prod.yml)
server {
    listen 8080;
    server_name ${SERVER_NAME};
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location = /healthz { access_log off; default_type text/plain; return 200 "ok\n"; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 8443 ssl;
    http2 on;
    server_name ${SERVER_NAME};
    ssl_certificate     /etc/letsencrypt/live/${SERVER_NAME}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${SERVER_NAME}/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;
    include /etc/nginx/snippets/app.conf;
}
```

```nginx
# frontend/nginx/snippets/security-headers.conf
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "no-referrer" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;
add_header Strict-Transport-Security "max-age=31536000" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
```

```nginx
# frontend/nginx/snippets/proxy.conf
proxy_pass http://api;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
# Never replay a request on another replica: a POST that timed out may already have moved money.
# The client's Idempotency-Key is the only safe retry mechanism.
proxy_next_upstream off;
proxy_read_timeout 30s;
include /etc/nginx/snippets/security-headers.conf;
add_header X-Upstream $upstream_debug always;
```

```nginx
# frontend/nginx/snippets/app.conf  (server body shared by the HTTP and TLS servers)
root /usr/share/nginx/html;
index index.html;
server_tokens off;
client_max_body_size 16k;
include /etc/nginx/snippets/security-headers.conf;

location = /healthz {
    access_log off;
    default_type text/plain;
    return 200 "ok\n";
}

location /.well-known/acme-challenge/ {
    root /var/www/certbot;
}

# Strict: 10 per minute per address, with a burst of 20, is plenty for a person and slow for a
# password guesser.
location /api/v1/auth/ {
    limit_req zone=auth burst=20 nodelay;
    include /etc/nginx/snippets/proxy.conf;
}

location /api/ {
    limit_req zone=api burst=200 nodelay;
    include /etc/nginx/snippets/proxy.conf;
}

# Vite fingerprints everything under /assets, so it can be cached forever.
location /assets/ {
    include /etc/nginx/snippets/security-headers.conf;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
    try_files $uri =404;
}

location / {
    try_files $uri /index.html;
}
```

```dockerfile
# frontend/Dockerfile
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Unprivileged variant: listens on 8080 and runs as uid 101, never root.
FROM nginxinc/nginx-unprivileged:1.28-alpine
COPY nginx/snippets/ /etc/nginx/snippets/
COPY nginx/templates/ /etc/nginx/templates/
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 8080 8443
```

```
# frontend/.dockerignore
node_modules
dist
```

- [ ] **Step 2: Build and syntax-check nginx**

Run (repo root): `docker build -t wallet-ledger-web:local frontend`
Run: `docker run --rm -e API_PORT=8091 -e NGINX_EXPOSE_UPSTREAM=off --add-host api:127.0.0.1 wallet-ledger-web:local sh -c "/docker-entrypoint.d/20-envsubst-on-templates.sh && nginx -t"`
Expected: `syntax is ok` / `test is successful`. If `resolve` is rejected, read the nginx version with `nginx -v` before touching the config — it must be ≥ 1.27.3. (`--add-host` is only there so `nginx -t` can resolve `api` at load time.)

- [ ] **Step 3: Confirm non-root**

Run: `docker run --rm --entrypoint id wallet-ledger-web:local` → `uid=101`.

- [ ] **Step 4: Commit**

```bash
git add frontend/nginx frontend/Dockerfile frontend/.dockerignore
git commit -m "feat: serve the SPA from unprivileged nginx with rate limits and security headers" -m "The API upstream re-resolves Docker DNS (server api resolve), so scaled or restarted replicas are load-balanced without an nginx restart. proxy_next_upstream is off so a POST is never replayed on a second replica. Auth is limited to 10 requests a minute per address. The full header set is included in every block that adds a header, because nginx does not merge add_header across levels."
```

---

### Task 8: The full compose stack and a smoke test

**Files:**
- Modify: `docker-compose.yml`, `.env.example`
- Create: `scripts/smoke.sh`

**Interfaces:**
- Produces: `docker compose up -d --build --wait` (1 replica) and `--scale api=3`; compose project name `wallet-ledger` ⇒ network `wallet-ledger_default` (Task 9 depends on this exact name); images `ghcr.io/hieuxuan1112/wallet-ledger-{api,web}:${IMAGE_TAG:-local}` (Task 10 pushes these names). `scripts/smoke.sh <base-url>` exits non-zero on any failed check (Task 10's deploy uses it).

- [ ] **Step 1: Rewrite `docker-compose.yml`**

```yaml
# One file for local, CI and production. docker-compose.prod.yml adds only TLS and replicas.
name: wallet-ledger

services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-wallet}
      POSTGRES_USER: ${POSTGRES_USER:-wallet}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
    ports:
      # Loopback only: reachable for local debugging and from the VM itself, never from outside.
      - "127.0.0.1:${DB_PORT:-55433}:5432"
    volumes:
      - db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-wallet} -d ${POSTGRES_DB:-wallet}"]
      interval: 5s
      timeout: 3s
      retries: 10
    restart: unless-stopped

  kafka:
    image: apache/kafka-native:3.8.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,EXTERNAL://0.0.0.0:9094
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:${KAFKA_PORT:-19092}
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "127.0.0.1:${KAFKA_PORT:-19092}:9094"
    healthcheck:
      # The native image has no Kafka CLI tools, but it has nc.
      test: ["CMD-SHELL", "nc -z localhost 9092"]
      interval: 5s
      timeout: 3s
      retries: 20
    restart: unless-stopped

  api:
    build: ./backend
    image: ghcr.io/hieuxuan1112/wallet-ledger-api:${IMAGE_TAG:-local}
    environment:
      DB_HOST: db
      DB_PORT: 5432                   # the container port, not the host's DB_PORT
      POSTGRES_DB: ${POSTGRES_DB:-wallet}
      POSTGRES_USER: ${POSTGRES_USER:-wallet}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}
      APP_JWT_SECRET: ${APP_JWT_SECRET:?APP_JWT_SECRET is required}
      API_PORT: 8091
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      APP_EVENTS_PUBLISHER: kafka
      APP_OUTBOX_RELAY_ENABLED: "true"
      APP_LEDGER_MUTATOR: ${APP_LEDGER_MUTATOR:-pessimistic}
    # No ports: scaled replicas cannot share a host port, and only nginx should reach them.
    depends_on:
      db: { condition: service_healthy }
      kafka: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8091/actuator/health | grep -q UP"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 60s
    mem_limit: 768m
    restart: unless-stopped

  web:
    build: ./frontend
    image: ghcr.io/hieuxuan1112/wallet-ledger-web:${IMAGE_TAG:-local}
    environment:
      API_PORT: 8091
      NGINX_EXPOSE_UPSTREAM: ${NGINX_EXPOSE_UPSTREAM:-off}
      SERVER_NAME: ${SERVER_NAME:-localhost}
    ports:
      - "${WEB_PORT:-8095}:8080"
    depends_on:
      api: { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/healthz"]
      interval: 10s
      timeout: 3s
      retries: 6
    restart: unless-stopped

volumes:
  db-data:
```

- [ ] **Step 2: Update `.env.example`**

```
# Ports. Chosen to avoid the 52 containers already running on this workstation.
# WEB_PORT is the only port published on all interfaces; production sets it to 80.
WEB_PORT=8095
# Inside the api container only (it is scaled, so it has no host port).
API_PORT=8091
# Host ports for local debugging, bound to 127.0.0.1.
DB_PORT=55433
KAFKA_PORT=19092

# Database
POSTGRES_DB=wallet
POSTGRES_USER=wallet
# Generate: openssl rand -hex 24
POSTGRES_PASSWORD=

# JWT signing key, at least 32 characters. Deliberately empty here:
# a missing value must stop startup, never fall back to a default.
# Generate: openssl rand -base64 48
APP_JWT_SECRET=

# Production only: the public host name (see docs/DEPLOY.md), and which image to run.
SERVER_NAME=localhost
IMAGE_TAG=local
```

- [ ] **Step 3: Write `scripts/smoke.sh`**

```bash
#!/usr/bin/env bash
# Usage: scripts/smoke.sh http://localhost:8095
# Checks a running stack end to end through nginx. Exits non-zero on the first failed check.
set -euo pipefail
BASE="${1:?base url required}"
USER_NAME="smoke-$(date +%s)"
PASS="smoke-test-password"

check() { if [ "$2" != "$3" ]; then echo "FAIL: $1 (expected $3, got $2)"; exit 1; fi; echo "ok: $1"; }
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

check "healthz" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/healthz")" 200
check "api refuses anonymous" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/wallet")" 401
check "security header" "$(curl -sI "$BASE/" | grep -ci '^x-frame-options: DENY')" 1

body="{\"username\":\"$USER_NAME\",\"password\":\"$PASS\"}"
check "register" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "$body" "$BASE/api/v1/auth/register")" 201
TOKEN="$(curl -s -H 'Content-Type: application/json' -d "$body" "$BASE/api/v1/auth/login" | field accessToken)"
KEY="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N)"
check "deposit" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d '{"amount":"10.0000"}' "$BASE/api/v1/wallet/deposits")" 201
check "balance" "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/wallet" | field balance)" 10.0000
check "statement paging shape" "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/statement" | grep -c '"totalPages"')" 1
echo "smoke test passed"
```

- [ ] **Step 4: Bring it up with three replicas and smoke it**

Create a local `.env` from `.env.example` with generated secrets (owner's file, never committed): `POSTGRES_PASSWORD=$(openssl rand -hex 24)`, `APP_JWT_SECRET=$(openssl rand -base64 48)`.
Run: `docker compose config --quiet` → no output.
Run: `docker compose up -d --build --scale api=3 --wait` → exits 0 with every service healthy.
Run: `docker compose ps` → three `api` rows, all `healthy`.
Run: `bash scripts/smoke.sh http://localhost:8095` → `smoke test passed`.
Run: `docker compose logs api | grep -c "Started WalletLedgerApplication"` → `3`.

Then open `http://localhost:8095` in the built-in browser: register, deposit, transfer to a second account, open the statement, refund, open notifications. `read_console_messages` must show **no CSP violation**. If the only violation is an inline style from a dependency, the fix is to add `'unsafe-inline'` to `style-src` only, and say so in the report — not to widen `script-src`.

- [ ] **Step 5: Prove a missing secret stops the stack**

Run: `APP_JWT_SECRET= docker compose config --quiet`
Expected: error `APP_JWT_SECRET is required`.

Run: `docker compose down` (keep the volume).

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml .env.example scripts/smoke.sh
git commit -m "feat: run api, web, db and kafka as one compose stack that scales to three API replicas" -m "The API has no host port, so --scale api=3 works and only nginx reaches it; DB and Kafka bind to loopback. Every service has a healthcheck and web waits for a healthy API. Secrets use the :? form, so a missing one stops compose before any container starts. scripts/smoke.sh checks the running stack end to end through nginx."
```

---

### Task 9: `MultiInstanceIT` and the `synchronized` control experiment

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/com/walletledger/multiinstance/StackClient.java`, `MultiInstanceIT.java`, `SynchronizedControlIT.java`
- Create: `scripts/multi-instance.sh`

**Interfaces:**
- Consumes: the stack from Task 8 on network `wallet-ledger_default` (service names `web:8080`, `db:5432`, `kafka:9092`), `X-Upstream` (Task 7), `APP_LEDGER_MUTATOR` (Task 3), `KafkaEventPublisher.TOPIC = "wallet-events"`, key = `ledger_transaction.id` as a string (existing).
- Produces: `bash scripts/multi-instance.sh` — exits 0 only if both scenarios pass (Task 10 CI runs it).

How "exactly once" is measured: the test collects the `transactionId` of every 201, maps each to `ledger_transaction.id` (the Kafka key), waits until the relays on all three JVMs have marked those outbox rows published, then reads the whole `wallet-events` topic and counts each key. A key seen twice means two relays published the same row; a key never seen means one was lost (Task 2).

Why the control experiment is expected to show **server errors, not lost money**: `Account` has `@Version` (bug #13), so a stale write from a second JVM is rejected by Hibernate with `ObjectOptimisticLockingFailureException` → HTTP 500. The balance stays exact; what fails is the promise that a legitimate request succeeds. Inside one JVM the same strategy produced zero such failures (Task 3's `SynchronizedConcurrencyIT`) — the difference is the second and third JVM.

- [ ] **Step 1: Exclude the tag from the default build and add the profile**

In `backend/pom.xml`, give the existing failsafe plugin a configuration:

```xml
        <configuration>
          <!-- Needs a live docker compose stack; run it with scripts/multi-instance.sh. -->
          <excludedGroups>multi-instance</excludedGroups>
        </configuration>
```

And add after `</build>`:

```xml
  <profiles>
    <profile>
      <id>multi-instance</id>
      <properties>
        <!-- Measures the running stack, not this JVM's code: coverage would read ~0% and fail the gate. -->
        <jacoco.skip>true</jacoco.skip>
      </properties>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <configuration>
              <groups>multi-instance</groups>
              <excludedGroups>none</excludedGroups>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

- [ ] **Step 2: Write `StackClient`**

```java
// backend/src/test/java/com/walletledger/multiinstance/StackClient.java
package com.walletledger.multiinstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.outbox.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Talks to a running docker compose stack the way a client does: over HTTP through nginx. The
 * database and Kafka are read only to check what the HTTP responses cannot show. Addresses are
 * compose service names because scripts/multi-instance.sh runs this JVM on the compose network.
 */
final class StackClient {

    record Outcome(int status, String transactionId, String upstream) {
    }

    private static final String PASSWORD = "multi-instance-account";

    private final String base = env("MI_BASE_URL", "http://web:8080");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    String signUp(String username) throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
        send("POST", "/api/v1/auth/register", null, null, body);
        HttpResponse<String> login = send("POST", "/api/v1/auth/login", null, null, body);
        return json.readTree(login.body()).get("accessToken").asText();
    }

    Outcome post(String path, String token, String body) throws Exception {
        HttpResponse<String> response = send("POST", path, token, UUID.randomUUID().toString(), body);
        String transactionId = response.statusCode() == 201
                ? json.readTree(response.body()).get("transactionId").asText()
                : null;
        return new Outcome(response.statusCode(), transactionId,
                response.headers().firstValue("X-Upstream").orElse(""));
    }

    JsonNode get(String path, String token) throws Exception {
        return json.readTree(send("GET", path, token, null, null).body());
    }

    /** Every request released at once by a start gate, each with its own Idempotency-Key. */
    List<Outcome> concurrentWithdrawals(String token, int threads, String amount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        String body = "{\"amount\":\"%s\",\"description\":\"multi-instance\"}".formatted(amount);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post("/api/v1/wallet/withdrawals", token, body);
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    Connection db() throws Exception {
        return DriverManager.getConnection(
                env("MI_DB_URL", "jdbc:postgresql://db:5432/" + env("POSTGRES_DB", "wallet")),
                env("POSTGRES_USER", "wallet"), System.getenv("POSTGRES_PASSWORD"));
    }

    /** [cached balance, balance derived from ledger_entry] for the wallet of this user. */
    BigDecimal[] cachedAndDerivedBalance(String username) throws Exception {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("""
                select a.balance, coalesce((select sum(e.amount) from ledger_entry e where e.account_id = a.id), 0)
                  from account a join app_user u on u.id = a.owner_user_id
                 where u.username = ? and a.type = 'USER_WALLET'""")) {
            s.setString(1, username);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return new BigDecimal[]{r.getBigDecimal(1), r.getBigDecimal(2)};
            }
        }
    }

    /** ledger_transaction.id as a string: the key KafkaEventPublisher sends each event under. */
    Set<String> kafkaKeysFor(Collection<String> publicIds) throws Exception {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement(
                "select id from ledger_transaction where public_id = any(?)")) {
            Array ids = c.createArrayOf("uuid", publicIds.stream().map(UUID::fromString).toArray());
            s.setArray(1, ids);
            Set<String> keys = new java.util.HashSet<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    keys.add(Long.toString(r.getLong(1)));
                }
            }
            return keys;
        }
    }

    long unpublishedOutboxRows(Set<String> keys) throws Exception {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement(
                "select count(*) from outbox_event where published_at is null and aggregate_id = any(?)")) {
            s.setArray(1, c.createArrayOf("bigint", keys.stream().map(Long::valueOf).toArray()));
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /** How many times each of these keys appears on the topic, reading it from the beginning. */
    Map<String, Integer> topicCounts(Set<String> keys, Duration settle) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, env("MI_KAFKA", "kafka:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "multi-instance-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Map<String, Integer> counts = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props,
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaEventPublisher.TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            long quietUntil = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, quietUntil)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (keys.contains(record.key())) {
                        counts.merge(record.key(), 1, Integer::sum);
                    }
                }
                // Once every key is seen, keep reading a little longer so a duplicate has time to show.
                if (quietUntil == Long.MAX_VALUE && counts.keySet().containsAll(keys)) {
                    quietUntil = System.nanoTime() + settle.toNanos();
                }
            }
        }
        return counts;
    }

    static void awaitUntil(ThrowingCondition condition, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.met()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutSeconds + "s");
            }
            Thread.sleep(500);
        }
    }

    interface ThrowingCondition {
        boolean met() throws Exception;
    }

    private HttpResponse<String> send(String method, String path, String token, String key, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
```

- [ ] **Step 3: Write the two tests**

```java
// backend/src/test/java/com/walletledger/multiinstance/MultiInstanceIT.java
package com.walletledger.multiinstance;

import com.walletledger.multiinstance.StackClient.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec section 10, multi-instance row, and the Phase 3 milestone. 100 withdrawals of 1.0000
 * against a wallet holding 50.0000, through nginx, spread over three API JVMs sharing one
 * database and one Kafka. Run by scripts/multi-instance.sh, never by the default mvn verify.
 */
@Tag("multi-instance")
class MultiInstanceIT {

    private final StackClient stack = new StackClient();

    @Test
    void threeJvmsKeepTheBalanceExactAndPublishEveryEventExactlyOnce() throws Exception {
        assertThat(System.getenv("MI_MUTATOR")).as("stack must run the production strategy").isEqualTo("pessimistic");
        String username = "mi-" + UUID.randomUUID();
        String token = stack.signUp(username);
        Outcome deposit = stack.post("/api/v1/wallet/deposits", token, "{\"amount\":\"50.0000\"}");
        assertThat(deposit.status()).isEqualTo(201);

        List<Outcome> outcomes = stack.concurrentWithdrawals(token, 100, "1.0000");

        Map<Integer, Long> byStatus = outcomes.stream()
                .collect(Collectors.groupingBy(Outcome::status, Collectors.counting()));
        Set<String> replicas = outcomes.stream().map(Outcome::upstream).collect(Collectors.toSet());
        System.out.printf("pessimistic x3: statuses=%s replicas=%s%n", byStatus, replicas);

        assertThat(replicas).as("nginx spread the load over three JVMs").hasSize(3);
        assertThat(byStatus).containsOnlyKeys(201, 409);
        assertThat(byStatus.get(201)).isEqualTo(50L);
        assertThat(stack.get("/api/v1/wallet", token).get("balance").asText()).isEqualTo("0.0000");
        BigDecimal[] balances = stack.cachedAndDerivedBalance(username);
        assertThat(balances[0]).isEqualByComparingTo(balances[1]).isEqualByComparingTo("0");

        List<String> publicIds = new ArrayList<>();
        publicIds.add(deposit.transactionId());
        outcomes.stream().filter(o -> o.status() == 201).forEach(o -> publicIds.add(o.transactionId()));
        Set<String> keys = stack.kafkaKeysFor(publicIds);
        assertThat(keys).hasSize(51);

        StackClient.awaitUntil(() -> stack.unpublishedOutboxRows(keys) == 0, 60);
        Map<String, Integer> counts = stack.topicCounts(keys, Duration.ofSeconds(5));
        System.out.printf("events on topic: %d distinct keys, max copies of one key=%d%n",
                counts.size(), counts.values().stream().mapToInt(Integer::intValue).max().orElse(0));

        assertThat(counts.keySet()).as("no event lost").containsExactlyInAnyOrderElementsOf(keys);
        assertThat(counts.values()).as("no event published twice").allMatch(copies -> copies == 1);
    }
}
```

```java
// backend/src/test/java/com/walletledger/multiinstance/SynchronizedControlIT.java
package com.walletledger.multiinstance;

import com.walletledger.multiinstance.StackClient.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same load as MultiInstanceIT against a stack started with APP_LEDGER_MUTATOR=synchronized:
 * each JVM serialises its own threads with a Java lock, and nothing serialises the three JVMs
 * against each other. Inside one JVM this strategy passed the full contract
 * (SynchronizedConcurrencyIT). Here it must not.
 */
@Tag("multi-instance")
class SynchronizedControlIT {

    private final StackClient stack = new StackClient();

    @Test
    void aJvmLocalLockDoesNotSerialiseThreeJvms() throws Exception {
        assertThat(System.getenv("MI_MUTATOR")).as("stack must run the control strategy").isEqualTo("synchronized");
        String username = "mi-sync-" + UUID.randomUUID();
        String token = stack.signUp(username);
        assertThat(stack.post("/api/v1/wallet/deposits", token, "{\"amount\":\"50.0000\"}").status()).isEqualTo(201);

        List<Outcome> outcomes = stack.concurrentWithdrawals(token, 100, "1.0000");

        Map<Integer, Long> byStatus = outcomes.stream()
                .collect(Collectors.groupingBy(Outcome::status, Collectors.counting()));
        long succeeded = byStatus.getOrDefault(201, 0L);
        long serverErrors = outcomes.stream().filter(o -> o.status() >= 500).count();
        System.out.printf("synchronized x3: statuses=%s%n", byStatus);

        // The failure: requests the money would have covered were rejected because another JVM
        // changed the row underneath a lock this JVM believed was exclusive.
        assertThat(serverErrors).as("version conflicts across JVMs").isPositive();
        // And why it is not worse: @Version (bug #13) still stops a stale write, so no money is
        // lost — the balance is exactly 50 minus what really succeeded.
        BigDecimal[] balances = stack.cachedAndDerivedBalance(username);
        assertThat(balances[0]).isEqualByComparingTo(balances[1])
                .isEqualByComparingTo(new BigDecimal(50 - succeeded));
    }
}
```

- [ ] **Step 4: Write `scripts/multi-instance.sh`**

```bash
#!/usr/bin/env bash
# Runs MultiInstanceIT (production locking) and SynchronizedControlIT (Java lock) against
# docker compose with three API replicas. Needs .env (see .env.example).
# The tests run in a Maven container on the compose network, so the command is the same on
# Windows (Git Bash) and in CI. M2_MOUNT: named volume locally, the runner's ~/.m2 in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd -W 2>/dev/null || pwd)"   # D:/... under Git Bash, /home/... on Linux
M2_MOUNT="${M2_MOUNT:-wallet-m2:/root/.m2}"
export NGINX_EXPOSE_UPSTREAM=on

trap 'docker compose down -v >/dev/null 2>&1 || true' EXIT

run_scenario() {
  local mutator=$1 test=$2
  echo "=== $test against APP_LEDGER_MUTATOR=$mutator, 3 replicas"
  APP_LEDGER_MUTATOR=$mutator docker compose up -d --build --scale api=3 --wait
  MSYS_NO_PATHCONV=1 docker run --rm --network wallet-ledger_default --env-file .env \
    -e MI_MUTATOR="$mutator" -v "$ROOT/backend:/app" -v "$M2_MOUNT" -w /app \
    maven:3.9-eclipse-temurin-21 \
    mvn -B verify -Pmulti-instance "-Dit.test=$test" -DfailIfNoSpecifiedTests=false
  docker compose down -v
}

run_scenario pessimistic MultiInstanceIT
run_scenario synchronized SynchronizedControlIT
echo "multi-instance: both scenarios passed"
```

- [ ] **Step 5: Prove the default build does not run them**

Run: `MVN_FULL`
Expected: green, same count as Task 3 Step 5 (124), and `grep -l MultiInstanceIT backend/target/failsafe-reports/*.xml` finds nothing.

- [ ] **Step 6: Run both scenarios**

Run (repo root, Git Bash): `bash scripts/multi-instance.sh 2>&1 | tee "$TEMP/../multi-instance.log"` — no: C: is full. Use `bash scripts/multi-instance.sh 2>&1 | tee D:/multi-instance.log`.
Expected: both tests pass; the log contains the two `statuses=` lines. Record them verbatim — they are the measured result for `HOC_DONG_THOI_VA_KHOA.md` and the PR.
If `SynchronizedControlIT` shows zero server errors, do **not** loosen the assertion: first read the three api logs for `ObjectOptimisticLockingFailureException`, and check the replicas line printed by `MultiInstanceIT` really had 3 entries. A zero is evidence only after both are confirmed; then report it honestly (systematic-debugging).

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/src/test/java/com/walletledger/multiinstance scripts/multi-instance.sh
git commit -m "test: prove three JVMs behind nginx keep money exact and publish each event once" -m "MultiInstanceIT sends 100 withdrawals of 1 against a 50 balance through nginx to three API replicas: exactly 50 succeed, the rest are 409, the cached balance equals the ledger, and every one of the 51 transactions appears on wallet-events exactly once. SynchronizedControlIT runs the same load with a Java lock instead of FOR UPDATE and shows it failing across JVMs. Both are tagged multi-instance, excluded from mvn verify, and run by scripts/multi-instance.sh from a Maven container on the compose network."
```

---

### Task 10: GitHub Actions

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `scripts/multi-instance.sh` (Task 9), `scripts/smoke.sh` (Task 8), image names (Task 8).
- Owner-provided (names only; values are set by the owner): secrets `VM_HOST`, `VM_USER`, `VM_SSH_KEY`, `VM_KNOWN_HOSTS`; variables `DEPLOY_ENABLED` (`true` once the VM exists), `PUBLIC_HOST`. No registry secret: GHCR uses the workflow's own `GITHUB_TOKEN`.

- [ ] **Step 1: Write the workflow**

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  secret-scan:
    name: Secret scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0   # scan every commit, not only the tip
      - uses: gitleaks/gitleaks-action@v3
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  backend:
    name: Backend (unit + integration + coverage gate)
    needs: secret-scan
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
      - name: mvn verify
        working-directory: backend
        run: mvn -B verify
      - name: Coverage figures
        if: always()
        working-directory: backend
        run: |
          awk -F, 'NR>1 {mi+=$4; ci+=$5; mb+=$6; cb+=$7} END {printf "instruction %.1f%%  branch %.1f%%\n", 100*ci/(mi+ci), 100*cb/(mb+cb)}' target/site/jacoco/jacoco.csv | tee -a "$GITHUB_STEP_SUMMARY"
      # A green build does not prove the suites ran: count what actually executed.
      - name: Assert the suites ran
        working-directory: backend
        run: |
          total() { grep -ho 'tests="[0-9]*"' "$1"/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'; }
          it=$(total target/failsafe-reports); unit=$(total target/surefire-reports)
          echo "integration=$it unit=$unit" | tee -a "$GITHUB_STEP_SUMMARY"
          [ "$it" -ge 110 ] && [ "$unit" -ge 5 ]
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: jacoco-report
          path: backend/target/site/jacoco/

  frontend:
    name: Frontend (lint + test + build)
    needs: secret-scan
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '24'
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: npm ci
      - run: npm run lint
      - run: npx vitest --run
      - run: npm run build

  multi-instance:
    name: Multi-instance (3 JVMs behind nginx)
    needs: [backend, frontend]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: m2-${{ hashFiles('backend/pom.xml') }}
      # Throwaway values generated per run. Nothing here is reused or committed.
      - name: Generate throwaway secrets
        run: |
          cp .env.example .env
          sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 24)|" .env
          sed -i "s|^APP_JWT_SECRET=.*|APP_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')|" .env
      - run: bash scripts/multi-instance.sh
        env:
          # The same directory actions/cache restores, so dependencies are not re-downloaded.
          M2_MOUNT: /home/runner/.m2:/root/.m2
      - name: Stack logs on failure
        if: failure()
        run: docker compose logs --no-color | tail -300

  images:
    name: Multi-arch images
    needs: multi-instance
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: docker/build-push-action@v6
        with:
          context: backend
          platforms: linux/amd64,linux/arm64
          push: true
          tags: |
            ghcr.io/hieuxuan1112/wallet-ledger-api:${{ github.sha }}
            ghcr.io/hieuxuan1112/wallet-ledger-api:latest
          cache-from: type=gha,scope=api
          cache-to: type=gha,mode=max,scope=api
      - uses: docker/build-push-action@v6
        with:
          context: frontend
          platforms: linux/amd64,linux/arm64
          push: true
          tags: |
            ghcr.io/hieuxuan1112/wallet-ledger-web:${{ github.sha }}
            ghcr.io/hieuxuan1112/wallet-ledger-web:latest
          cache-from: type=gha,scope=web
          cache-to: type=gha,mode=max,scope=web

  deploy:
    name: Deploy to the Oracle VM
    needs: images
    # Off until the owner has a VM and sets the repository variable DEPLOY_ENABLED=true.
    if: github.ref == 'refs/heads/main' && vars.DEPLOY_ENABLED == 'true'
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: actions/checkout@v4
      - name: SSH identity
        run: |
          install -m 700 -d ~/.ssh
          printf '%s\n' "${{ secrets.VM_SSH_KEY }}" > ~/.ssh/id_ed25519 && chmod 600 ~/.ssh/id_ed25519
          # Pinned host key instead of StrictHostKeyChecking=no, which would trust any server.
          printf '%s\n' "${{ secrets.VM_KNOWN_HOSTS }}" > ~/.ssh/known_hosts
      - name: Ship the compose files
        run: |
          tar czf - docker-compose.yml docker-compose.prod.yml frontend/nginx/tls.conf.template scripts/backup-db.sh \
            | ssh "${{ secrets.VM_USER }}@${{ secrets.VM_HOST }}" "mkdir -p ~/wallet-ledger && tar xzf - -C ~/wallet-ledger"
      - name: Roll out ${{ github.sha }}
        run: |
          ssh "${{ secrets.VM_USER }}@${{ secrets.VM_HOST }}" "cd ~/wallet-ledger && \
            export IMAGE_TAG=${{ github.sha }} && \
            docker compose -f docker-compose.yml -f docker-compose.prod.yml pull api web && \
            docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build --wait && \
            echo \"\$(date -u +%FT%TZ) ${{ github.sha }}\" >> releases.log"
      - name: Smoke test the live URL
        run: bash scripts/smoke.sh "https://${{ vars.PUBLIC_HOST }}"
```

- [ ] **Step 2: Lint the workflow and run the secret scan locally**

Run: `docker run --rm -v "D:/wallet-ledger:/repo" -w /repo rhysd/actionlint:latest -color` → no findings.
Run: `docker run --rm -v "D:/wallet-ledger:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact -v`
Expected: `no leaks found`. If a finding is a test fixture (e.g. `TEST_PASSWORD`), add a `.gitleaks.toml` allowlist entry naming that one path and rule — never a blanket path like `backend/src/test/**`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: secret scan, backend, frontend, multi-instance, multi-arch images and gated deploy" -m "gitleaks scans full history first; backend and frontend run in parallel after it; the multi-instance job runs both scenarios against three real replicas; images for amd64 and arm64 are pushed to GHCR only from main. Deploy stays off until the repository variable DEPLOY_ENABLED is true, pins the VM host key, and ends with the smoke test against the live URL."
```

The owner then pushes and watches the first run; CI status is read with the `ccd_pr` tools after the PR exists.

---

### Task 11: Production overlay, runbook and backups

**Files:**
- Create: `docker-compose.prod.yml`, `scripts/backup-db.sh`, `docs/DEPLOY.md`

- [ ] **Step 1: Write the overlay**

```yaml
# docker-compose.prod.yml — used only on the VM, on top of docker-compose.yml:
#   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
services:
  api:
    deploy:
      replicas: 3

  web:
    ports:
      - "443:8443"
    volumes:
      - ./frontend/nginx/tls.conf.template:/etc/nginx/templates/default.conf.template:ro
      - letsencrypt:/etc/letsencrypt:ro
      - certbot-www:/var/www/certbot:ro

  certbot:
    image: certbot/certbot:v5.8.0
    volumes:
      - letsencrypt:/etc/letsencrypt
      - certbot-www:/var/www/certbot
    # Renews when due (certbot itself decides); the deploy hook saved at issuance hands the
    # files to nginx's uid 101, since the unprivileged nginx cannot read root-only keys.
    entrypoint: ["/bin/sh", "-c", "trap exit TERM; while :; do certbot renew --webroot -w /var/www/certbot --quiet; sleep 12h & wait $${!}; done"]
    healthcheck:
      test: ["CMD-SHELL", "test -d /etc/letsencrypt/live"]
      interval: 1h
    restart: unless-stopped

volumes:
  letsencrypt:
  certbot-www:
```

- [ ] **Step 2: Write the backup script**

```bash
#!/usr/bin/env bash
# Nightly logical backup. Cron on the VM: 15 3 * * * ~/wallet-ledger/scripts/backup-db.sh
# Restore: docker compose exec -T db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean < backups/<file>.dump
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
mkdir -p backups
docker compose exec -T db pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB" > "backups/wallet-$(date +%F).dump"
find backups -name 'wallet-*.dump' -mtime +14 -delete
```

- [ ] **Step 3: Write `docs/DEPLOY.md`** (Vietnamese, owner-facing runbook). Sections, each with the exact commands:

1. **Tạo VM** — Oracle Cloud Always Free, shape `VM.Standard.A1.Flex` 2 OCPU / 12 GB, Ubuntu 24.04 (aarch64). If the region reports "Out of capacity", retry later or another AD; fallback deployment is spec §11's Render + Neon + `LoggingEventPublisher` (documented, not built in this phase).
2. **Mở cổng** — VCN security list ingress TCP 80 and 443 from `0.0.0.0/0`; Oracle's Ubuntu image also REJECTs in iptables, so: `sudo iptables -I INPUT 6 -p tcp -m state --state NEW -m multiport --dports 80,443 -j ACCEPT && sudo netfilter-persistent save`. Do **not** open 55433 or 19092.
3. **Cài Docker** — `curl -fsSL https://get.docker.com | sh` then `sudo usermod -aG docker $USER`, re-login, `docker compose version`.
4. **Tên miền + TLS** — no domain: use `<ip-with-dashes>.sslip.io` (resolves to the IP, lets Let's Encrypt issue a real certificate); if issuance is rate-limited, a free DuckDNS name works the same way. First certificate, before the stack is up (port 80 free):
   `docker run --rm -p 80:80 -v wallet-ledger_letsencrypt:/etc/letsencrypt certbot/certbot:v5.8.0 certonly --standalone -d "$SERVER_NAME" -m <your-email> --agree-tos -n --deploy-hook "chown -R 101:101 /etc/letsencrypt/live /etc/letsencrypt/archive"`
   (The owner runs this: it accepts Let's Encrypt's terms.) Weekly nginx reload for renewed certs, cron: `0 4 * * 1 cd ~/wallet-ledger && docker compose -f docker-compose.yml -f docker-compose.prod.yml exec web nginx -s reload`.
5. **`.env` trên VM** — copy `.env.example`, set `WEB_PORT=80`, `SERVER_NAME=<host>`, generated `POSTGRES_PASSWORD` and `APP_JWT_SECRET`, `chmod 600 .env`. Note: `POSTGRES_PASSWORD` only applies when the volume is first created.
6. **Deploy key** — on the owner's machine `ssh-keygen -t ed25519 -f wallet-deploy -C github-actions`; append `wallet-deploy.pub` to the VM's `~/.ssh/authorized_keys`; `ssh-keyscan <ip>` output → secret `VM_KNOWN_HOSTS`; private key → secret `VM_SSH_KEY`; `VM_HOST`, `VM_USER` (usually `ubuntu`). Variables: `PUBLIC_HOST=<host>`, then `DEPLOY_ENABLED=true`. Create the `production` environment (optionally with required reviewers).
7. **GHCR** — after the first `images` run, open each package (`wallet-ledger-api`, `wallet-ledger-web`) → Package settings → confirm visibility **Public** (the VM pulls without logging in).
8. **Lần deploy đầu** — push to `main`, watch `deploy`, then `bash scripts/smoke.sh https://<host>`.
9. **Backup** — cron line from `scripts/backup-db.sh`; copy `backups/` off the VM periodically (OCI Object Storage or `scp` to the owner's machine) — a backup on the same disk is not a backup.
10. **Rollback** — `tail releases.log`, then `IMAGE_TAG=<previous sha> docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build --wait`. Flyway migrations are forward-only: a rollback across a migration needs a restore, which is why every migration so far is additive.
11. **Rotate secrets** — JWT: new value in `.env`, `up -d` (recreates api); every session ends, users sign in again. DB password: `docker compose exec db psql -U wallet -c "ALTER USER wallet PASSWORD '<new>'"` first, then `.env`, then `up -d`. Deploy key: generate new, add, update secret, remove old line from `authorized_keys`.
12. **Oracle idle reclaim** — Always Free instances with very low CPU over 7 days can be reclaimed; upgrading the account to Pay-As-You-Go (still free within limits) removes that risk.

- [ ] **Step 4: Validate the overlay**

Run: `docker compose -f docker-compose.yml -f docker-compose.prod.yml config --quiet` → no output (with the local `.env`).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.prod.yml scripts/backup-db.sh docs/DEPLOY.md
git commit -m "docs: add the production overlay and the Oracle VM runbook" -m "The overlay adds only what production needs: three replicas, TLS on 443 with a certbot renewer, and the TLS server template. The runbook covers VM creation, firewall, first certificate, deploy key, GHCR visibility, nightly pg_dump backups, rollback by image tag, and secret rotation."
```

---

### Task 12: Phase close — full verification, README, PR

- [ ] **Step 1:** `docker info` → running. `MVN_FULL` → green. Read `backend/target/site/jacoco/jacoco.csv` and compute instruction/branch % (same awk as CI).
- [ ] **Step 2:** `cd frontend && npx vitest --run && npm run lint && npm run build`.
- [ ] **Step 3:** `bash scripts/multi-instance.sh` → both scenarios pass; keep the two `statuses=` lines.
- [ ] **Step 4:** README: add the CI badge (`https://github.com/Hieuxuan1112/wallet-ledger/actions/workflows/ci.yml/badge.svg`), "Run it" (`cp .env.example .env`, fill secrets, `docker compose up -d --build --scale api=3 --wait`, open `http://localhost:8095`), the multi-instance result table, and the live URL once `deploy` has passed (until then: "deploy pending VM, see docs/DEPLOY.md").
- [ ] **Step 5:** Commit README; hand the owner the PR command:

```bash
git add README.md
git commit -m "docs: document how to run the stack and the multi-instance result"
git push -u origin phase3-spa-nginx-ci-deploy
"/c/Program Files/GitHub CLI/gh.exe" pr create --base phase2a-statement-refund --title "Phase 3: React SPA, nginx, multi-instance proof, CI and deployment" --body-file D:/phase3-pr.md
```

(`--base phase2a-statement-refund` while the Phase 1C+2 PR is open; retarget to `main` after it merges. The PR body is written to `D:/phase3-pr.md` with the real numbers from Steps 1 and 3.)

- [ ] **Step 6:** List the new NHAT_KY_BUG entries (at minimum: the asynchronous `send()` found in Task 2) as ready-to-paste Vietnamese text in the report.

## Definition of done for Phase 3

- [ ] `mvn -B verify` green with the coverage gate unchanged; `MultiInstanceIT` not among the executed tests
- [ ] `KafkaEventPublisher` waits for the ack; unit-proven
- [ ] `SynchronizedConcurrencyIT` green in one JVM; `SynchronizedControlIT` shows server errors across three
- [ ] `MultiInstanceIT` green: 3 distinct replicas, 50×201 + 50×409, cached = derived = 0, 51 keys each exactly once on `wallet-events`
- [ ] SPA: sign-in/register, wallet, deposit/withdraw, transfer, statement with paging/filter/refund, notifications; retried submissions reuse their `Idempotency-Key` (tested)
- [ ] nginx: non-root, security headers + CSP with no console violation, auth rate limit, no CORS headers, `X-Upstream` off by default
- [ ] Every compose service healthy; `--scale api=3` works; missing secret stops compose by name
- [ ] CI: secret scan → backend ∥ frontend → multi-instance → multi-arch images (main) → deploy (gated)
- [ ] **Milestone:** live URL passes `scripts/smoke.sh` — requires the owner's VM (Task 11 runbook); if the VM is not ready, Phase 3 closes with everything else done and the milestone explicitly marked pending

## Notes for the implementer

- **Do not run git.** Hand the owner the blocks above; no `Co-Authored-By`.
- **`C:` is full.** Anything that writes a cache or log goes to `D:` (`npm --cache D:/tmp-npm-cache`, `tee D:/...`). Docker Desktop's disk image must move to `D:` before the image builds in Tasks 4, 7, 8, 9 (each is several hundred MB).
- **Never two `mvn verify` at once** on `backend/` — including `scripts/multi-instance.sh`, which runs one inside its own container against the same `backend/target`.
- **Shared tables and topics:** `MultiInstanceIT` runs on a fresh stack (`down -v` between scenarios) and still filters the topic by its own keys, the Phase 2B rule.
- **Measure, do not assert beyond the evidence:** if the control experiment's numbers surprise, record them; bug #13 already showed this project's "unsafe" strategies are safer than they look.
