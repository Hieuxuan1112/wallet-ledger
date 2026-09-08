# Wallet + Double-Entry Ledger — Design

**Date:** 2026-09-08
**Owner:** Ngô Xuân Hiếu
**Status:** approved for planning

---

## 1. Goal

Build an e-wallet whose money movements are **accounting-correct** and **concurrency-correct**, then
add an AI layer that is allowed to *propose* but never to *move money on its own*.

The product is a means to an end. The end is a repository that demonstrates, with runnable evidence:

| Concept | Where it must appear in the code |
|---|---|
| Isolation levels (READ COMMITTED / REPEATABLE READ / SERIALIZABLE) | Concurrent transfer paths and reproduction tests |
| Pessimistic locking (`SELECT ... FOR UPDATE`) | Default balance mutation path |
| Optimistic locking (`@Version` + retry) | Alternate balance mutation path, measured against the default |
| Deadlock avoidance | Wallets locked in ascending account id order |
| Idempotency | `Idempotency-Key` header on every money-writing endpoint |
| Outbox pattern | DB write + Kafka publish made atomic |
| `@Transactional` propagation | `REQUIRES_NEW` for audit log |
| Lost update / non-repeatable read / phantom read | Tests that reproduce each (and prove dirty read cannot occur) |
| LLM safety in a system that must be correct | AI proposes a typed intent; a human confirms; execution reuses the locked, idempotent path |

### Non-goals

Stated explicitly so scope does not drift:

- No real payment gateway. Deposits and withdrawals are simulated.
- **No multi-currency.** Single implicit currency. A `currency` column without a conversion policy is
  worse than no column.
- **No `PENDING` transaction state.** A transaction either commits or does not exist. Hold/capture and
  two-phase settlement are a different problem.
- No partial refunds. Reversal is all-or-nothing.
- No MongoDB, no Firebase/Firestore. This problem needs multi-table ACID, `SELECT ... FOR UPDATE`,
  selectable isolation levels, and `EXPLAIN ANALYZE`; document stores provide none of them.
- No Supabase BaaS features (Auth, RLS, PostgREST). Using them would delete the Spring Security and
  authorization work that is part of the learning goal. Supabase remains acceptable *only* as a plain
  hosted PostgreSQL in the fallback deployment.
- No Kubernetes manifests. Horizontal-scaling correctness is proven with `docker compose --scale api=3`,
  which yields the same lesson for a fraction of the effort.

---

## 2. Architecture

```
Browser
   │ HTTPS
   ▼
nginx  ── serves the React SPA
   │   ── proxies /api/* to the API (round-robin across replicas)
   ▼
Spring Boot API  × N replicas          ──▶  Google Gemini (AI tasks)
   │  JDBC                    │ Kafka producer
   ▼                          ▼
PostgreSQL 16            Kafka (KRaft, single node)
                              │
                              ▼
                    @KafkaListener in the same app
                    (categorisation → transaction_category)
```

Backend layering follows SlangWord: `web → service → repository → domain`, dependency arrows point
downwards only. This is enforced by ArchUnit tests rather than by discipline alone (see §10).

Package layout:

```
com.walletledger
├── auth/          registration, login, JWT, refresh-token rotation
├── account/       Account entity, balance mutation strategies
├── ledger/        LedgerTransaction, LedgerEntry, invariants, statement
├── money/         DepositService, WithdrawalService, TransferService, RefundService
├── idempotency/   Idempotency-Key filter + store
├── outbox/        OutboxEvent, relay, EventPublisher
├── audit/         AuditLogger (REQUIRES_NEW)
├── ai/            LlmClient, intent parsing, categorisation consumer, guardrails
└── config/        properties, security, datasource
```

`ai` is a leaf: it may read ledger data, and it may write only its own tables. ArchUnit enforces this.

---

## 3. Data model

### 3.1 Accounts

```sql
CREATE TABLE account (
    id            BIGSERIAL PRIMARY KEY,
    type          VARCHAR(32)    NOT NULL,   -- USER_WALLET | SYSTEM_FUNDING | SYSTEM_PAYOUT
    owner_user_id BIGINT         NULL REFERENCES app_user(id),
    balance       NUMERIC(19,4)  NOT NULL DEFAULT 0,
    version       BIGINT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- exactly one wallet per user
CREATE UNIQUE INDEX uq_wallet_owner ON account (owner_user_id) WHERE type = 'USER_WALLET';

-- a user wallet may never go negative; system accounts may
ALTER TABLE account ADD CONSTRAINT ck_wallet_non_negative
    CHECK (type <> 'USER_WALLET' OR balance >= 0);
```

Two system accounts are seeded by the migration with fixed ids:

| id | type | Meaning |
|---|---|---|
| 1 | `SYSTEM_FUNDING` | The outside world funding deposits. Its balance goes negative; `abs(balance)` equals total money ever deposited. |
| 2 | `SYSTEM_PAYOUT` | The outside world receiving withdrawals. |

`balance` is a **cache**, not a second source of truth. It is updated in the same transaction that writes
the entries, and §4.3 defines how it is reconciled against the ledger.

### 3.2 Ledger

```sql
CREATE TABLE ledger_transaction (
    id                      BIGSERIAL PRIMARY KEY,
    public_id               UUID        NOT NULL UNIQUE,   -- exposed by the API; the sequence is not
    type                    VARCHAR(16) NOT NULL,          -- DEPOSIT|WITHDRAWAL|TRANSFER|REVERSAL
    reverses_transaction_id BIGINT      NULL UNIQUE REFERENCES ledger_transaction(id),
    initiated_by_user_id    BIGINT      NOT NULL REFERENCES app_user(id),
    description             VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reversal_link CHECK (
        (type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL))
);

CREATE TABLE ledger_entry (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT        NOT NULL REFERENCES ledger_transaction(id),
    account_id     BIGINT        NOT NULL REFERENCES account(id),
    amount         NUMERIC(19,4) NOT NULL CHECK (amount <> 0),   -- signed
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_entry_account_created ON ledger_entry (account_id, created_at DESC, id DESC);
```

`amount` is signed. A transfer of 100 from A to B is `(A, -100)` and `(B, +100)`. A deposit of 100 into A
is `(SYSTEM_FUNDING, -100)` and `(A, +100)`. The statement DTO derives `direction = DEBIT | CREDIT` from
the sign, so the API still reads like accounting while the storage keeps a single-column invariant.

`reverses_transaction_id UNIQUE` makes double-refund impossible at the database level.

### 3.3 Three database-level guarantees

Java code cannot corrupt the ledger even if it is wrong.

**(a) Wallets never go negative** — the `CHECK` above.

**(b) Entries are append-only.**

```sql
CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entry is append-only; % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
```

Corrections happen by posting a reversing transaction, never by editing history.
Known cost: a future migration that needs to touch `ledger_entry` must drop and recreate this trigger.

**(c) Every transaction balances, checked at COMMIT time.**

```sql
CREATE OR REPLACE FUNCTION assert_transaction_balanced() RETURNS trigger AS $$
DECLARE total NUMERIC(19,4);
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO total
      FROM ledger_entry WHERE transaction_id = NEW.transaction_id;
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

A normal constraint fires per row and would reject the first entry of every pair.
`DEFERRABLE INITIALLY DEFERRED` postpones the check to commit, when both entries exist.
This is the single most important line in the schema.

### 3.4 Global invariants

Each is one SQL statement, and each becomes an assertion in `LedgerInvariantIT`:

```sql
SELECT SUM(amount) FROM ledger_entry;   -- must be 0
SELECT SUM(balance) FROM account;       -- must be 0

-- cached balance must equal the ledger; result must be empty
SELECT a.id
  FROM account a LEFT JOIN ledger_entry e ON e.account_id = a.id
 GROUP BY a.id, a.balance
HAVING a.balance <> COALESCE(SUM(e.amount), 0);
```

### 3.5 Supporting tables

```sql
idempotency_key(id, user_id, idem_key, endpoint, request_hash CHAR(64),
                transaction_id NULL, response_status, response_body, created_at,
                UNIQUE (user_id, idem_key))

outbox_event(id, aggregate_type, aggregate_id, event_type, payload JSONB,
             created_at, published_at NULL, attempts DEFAULT 0)
CREATE INDEX idx_outbox_pending ON outbox_event (id) WHERE published_at IS NULL;

audit_log(id, user_id, action, detail, outcome, created_at)

notification(id, user_id, type, message, event_id UUID UNIQUE, created_at)

app_user(id, username UNIQUE, password_hash, role, created_at)
refresh_token(id, user_id, token_hash, family_id, used_at, revoked_at, expires_at, created_at)
```

### 3.6 AI tables

```sql
ai_intent(id, public_id UUID UNIQUE, user_id, kind,          -- TRANSFER | STATEMENT_QUERY
          raw_utterance TEXT, parsed_payload JSONB,
          status,                                             -- PENDING|CONFIRMED|REJECTED|EXPIRED
          confirmation_token_hash CHAR(64) UNIQUE, expires_at,
          executed_transaction_id BIGINT NULL UNIQUE REFERENCES ledger_transaction(id),
          model, created_at)

transaction_category(transaction_id PK REFERENCES ledger_transaction(id),
                     category, confidence NUMERIC(4,3), model,
                     event_id UUID UNIQUE, created_at)

llm_call_log(id, user_id NULL, task, model, input_tokens, output_tokens,
             latency_ms, outcome, estimated_cost_usd NUMERIC(12,6), created_at)
```

**Boundary rule:** LLM-derived data lives in its own tables. `account` and `ledger_entry` are written
only by the money services. `ai_intent.executed_transaction_id UNIQUE` means one proposal produces at
most one transaction, no matter how many times confirm is called.

### 3.7 Money representation

`BigDecimal` mapped to `NUMERIC(19,4)`. `double` and `float` are forbidden and the ban is enforced by an
ArchUnit rule, not by a promise. Request amounts with more than 4 decimal places are **rejected**, not
silently rounded. No rounding policy is needed because there are no fees, no interest, and reversals are
exact negations.

---

## 4. Concurrency design

### 4.1 Transfer flow

Isolation: READ COMMITTED (PostgreSQL default).

```
BEGIN
 1. INSERT INTO idempotency_key (...)      -- unique violation ⇒ replay / in-progress branch
 2. Lock wallets with SELECT ... FOR UPDATE, LOWER account id FIRST, then the higher one
 3. Verify source balance >= amount, else throw InsufficientFundsException
 4. INSERT ledger_transaction + 2 ledger_entry rows
 5. UPDATE account SET balance = balance ± amount, version = version + 1
 6. INSERT outbox_event
 7. UPDATE idempotency_key SET transaction_id, response_status, response_body
COMMIT                                     -- deferred trigger verifies SUM(amount) = 0
```

Step 2 is **two separate `SELECT ... FOR UPDATE` statements issued in ascending id order from Java**, not
a single `WHERE id IN (a, b) ORDER BY id FOR UPDATE`. PostgreSQL does not guarantee that row locks are
acquired in `ORDER BY` order, so only explicit sequential locking makes the ordering a real guarantee.
This is the mechanism that prevents A→B and B→A from deadlocking.

Audit logging goes through `AuditLogger`, a **separate bean** annotated `REQUIRES_NEW`, so a failed
transfer still leaves a trail after the outer transaction rolls back. It must be a separate bean: calling
a `REQUIRES_NEW` method on `this` bypasses the Spring proxy and silently joins the outer transaction.

### 4.2 Four balance-mutation strategies behind one interface

```java
interface BalanceMutator {
    TransferResult apply(long fromAccountId, long toAccountId, BigDecimal amount, ...);
}
```

| Implementation | Mechanism | What the shared test suite measures |
|---|---|---|
| `PessimisticBalanceMutator` (default) | `FOR UPDATE`, READ COMMITTED | wall time; must never be wrong |
| `OptimisticBalanceMutator` | `@Version`, `UPDATE ... WHERE version = ?`, bounded retry with jitter | retry count under 100 threads |
| `SerializableBalanceMutator` | no locks, SERIALIZABLE, retry on SQLSTATE `40001` | abort rate under 100 threads |
| `UnsafeBalanceMutator` (test sources only) | read → pause → write | reproduces lost update |

The same concurrency tests run against the first three, producing a real measured comparison table rather
than a textbook claim about when to use which.

### 4.3 Reconciliation

`GET /api/v1/admin/reconcile` runs the query in §3.4 and reports any account whose cached balance drifts
from the ledger. `LedgerInvariantIT` performs N randomised operations and asserts the result is empty.

### 4.4 Isolation-level reproduction tests

| Phenomenon | Expected result | Note |
|---|---|---|
| Lost update | Reproducible with `UnsafeBalanceMutator`; disappears with locking | |
| Non-repeatable read | Occurs at READ COMMITTED; gone at REPEATABLE READ | |
| Phantom read | Occurs at READ COMMITTED; gone at REPEATABLE READ | PostgreSQL's REPEATABLE READ is snapshot isolation and already blocks phantoms |
| **Dirty read** | **Cannot be reproduced** | PostgreSQL maps READ UNCOMMITTED to READ COMMITTED; MVCC never returns uncommitted rows. The test asserts the *absence* of the phenomenon rather than faking it. |

---

## 5. Idempotency

Stored in PostgreSQL, not Redis. The reason is atomicity: the idempotency record and the ledger entries
must commit together. Redis and PostgreSQL have no shared commit, so a failure between them either
double-charges the customer or loses the request. A `UNIQUE` constraint inside the same transaction is
both simpler and strictly more correct.

`Idempotency-Key` is required on every money-writing endpoint. Behaviour:

| Situation | Response |
|---|---|
| New key | Execute, store response, return it |
| Same key, same `request_hash`, original committed | `200` + stored response + header `Idempotency-Replayed: true` |
| Same key, **different** `request_hash` | `422` — the key was reused for a different request |
| Same key, original **still in flight** | `409` + `Retry-After` — the losing writer cannot read the winner's uncommitted response. Stripe behaves the same way. |

`request_hash` is SHA-256 over the canonicalised request body.

---

## 6. Refund

A refund creates a **new** `REVERSAL` transaction whose entries are the exact negation of the original,
linked by `reverses_transaction_id`. Nothing is edited or deleted.

Rules, each with a test:

- Only the initiator of the original transaction may refund it.
- A transaction may be refunded at most once (`UNIQUE` constraint).
- A `REVERSAL` cannot itself be refunded.
- **If the counterparty has already spent the money, the refund is rejected with `409 INSUFFICIENT_FUNDS`.**
  The "wallets never go negative" invariant is absolute; allowing negative wallets would mean losing the
  `CHECK` constraint and weakening the strongest guarantee in the system.
- Refund takes the same locking path as a transfer, only with the signs reversed.

---

## 7. Outbox and Kafka

The dual-write problem: writing to PostgreSQL and publishing to Kafka cannot both be in one transaction.
Solution: write the event to `outbox_event` inside the money transaction, then relay it.

```
@Scheduled relay:
  SELECT * FROM outbox_event WHERE published_at IS NULL
  ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100
  → publish to Kafka → UPDATE published_at
```

`FOR UPDATE SKIP LOCKED` is what makes the relay safe on 3 replicas without leader election — each
replica claims a disjoint batch. This is the property `MultiInstanceIT` verifies.

Delivery is **at-least-once**, so every consumer must be idempotent. `transaction_category.transaction_id`
is a primary key and `notification.event_id` is unique; a redelivered event is a no-op.

`EventPublisher` has two implementations, selected by profile:

| Implementation | Used where |
|---|---|
| `KafkaEventPublisher` | local, CI (Testcontainers), Oracle VM deployment |
| `LoggingEventPublisher` | fallback cloud deployment, where no free Kafka host exists |

---

## 8. AI layer

### 8.1 Principle

The LLM never moves money. It produces a typed proposal; a human confirms; execution reuses the same
locked, idempotent service that the normal API uses.

```
POST /api/v1/ai/intents            → LLM parses → validated TransferIntent → ai_intent (PENDING)
                                     no ledger row exists at this point
POST /api/v1/ai/intents/{id}/confirm  (Idempotency-Key, confirmation token)
                                   → TransferService (unchanged, locked, idempotent)
```

### 8.2 Six guardrails, each with a test

| Guardrail | Assertion |
|---|---|
| No LLM output reaches a ledger write without human confirmation | After `POST /ai/intents`, no `ledger_transaction` row exists |
| `user_id` always comes from the JWT, never from LLM output | A prompt asking for another user's statement returns only the caller's data |
| Output must parse into a closed schema | Malformed or out-of-schema output ⇒ `422`, never a best-effort guess |
| AI-originated transfers have their own limit | Exceeding `app.ai.transfer.max-amount` is rejected |
| `description` is treated as data, not instructions | A corpus of ~10 injection strings produces no effect |
| Proposals expire | Confirming after `expires_at` ⇒ `410 Gone` |

The injection corpus is exercised through a `FakeLlmClient` configured to return the **attacker's desired
output**. This tests the guardrail rather than the model — which is the correct thing to test, because
the guarantee must hold regardless of which model is used.

### 8.3 Async categorisation

`transaction.posted` → Kafka → `@KafkaListener` → LLM assigns one label from a closed set →
`transaction_category`. AI latency and AI outages therefore cannot slow down or break the money path.

### 8.4 Natural-language statement query

The LLM returns a validated `StatementQuery { from, to, counterparty, type }`. It never emits SQL. The
`user_id` is attached by the server after parsing.

### 8.5 Model routing

Library: **Spring AI 1.1.x** with `spring-ai-starter-model-google-genai`, configured via
`spring.ai.google.genai.api-key` (Google AI Studio key). Exact patch version to be pinned from Maven
Central during planning — see §12.

| Task | Model | Rationale |
|---|---|---|
| Transaction categorisation | `gemini-2.5-flash-lite` | Highest call volume, lowest difficulty: one sentence in, one closed-set label out |
| NL → `StatementQuery` | `gemini-2.5-flash-lite`, promoted only if evaluation says so | Relative dates ("last month") are where small models fail |
| `TransferIntent` extraction | `gemini-3.1-flash-lite` | An error here means a wrong amount or a wrong recipient |

Models are configured per task (`app.ai.models.<task>`), so the evaluation suite can run the same golden
set through both and produce an accuracy × cost table.

### 8.6 `LlmClient` — three implementations

| Implementation | Used where |
|---|---|
| `FakeLlmClient` | CI. Deterministic, no API key, no cost. Also used to inject adversarial outputs for guardrail tests. |
| `OllamaLlmClient` | Local development, free and offline |
| `GoogleGenAiLlmClient` | Real runs, via the Google AI Studio key |

### 8.7 Two evaluation tiers — stated honestly

| Tier | Runs where | Gates the build? | Measures |
|---|---|---|---|
| 1 — guardrails | CI, `FakeLlmClient`, no key | **Yes** | Whether the system refuses bad LLM output. This is what protects money. |
| 2 — model accuracy | Optional workflow, real key | No | Golden set × 2 models → accuracy and cost. Committed as a report. |

Tier 1 does not measure model quality and must not be described as if it does.

---

## 9. API surface

RFC 7807 `ProblemDetail` for every error, as in SlangWord.

```
POST   /api/v1/auth/register | login | refresh | logout
GET    /api/v1/wallet
POST   /api/v1/wallet/deposits                      Idempotency-Key
POST   /api/v1/wallet/withdrawals                   Idempotency-Key
POST   /api/v1/transfers                            Idempotency-Key
POST   /api/v1/transactions/{publicId}/refund       Idempotency-Key
GET    /api/v1/statement?from=&to=&type=&page=&size=
GET    /api/v1/admin/reconcile                      ADMIN
POST   /api/v1/ai/intents
POST   /api/v1/ai/intents/{publicId}/confirm        Idempotency-Key
POST   /api/v1/ai/statement-query
```

Status codes with meaning: `401` unauthenticated vs `403` unauthorised (SlangWord bug 10.1);
`409` for `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_IN_PROGRESS`, `ALREADY_REFUNDED`;
`410` for `INTENT_EXPIRED`; `422` for `IDEMPOTENCY_KEY_REUSED` and `AI_PARSE_FAILED`.

Statement paging starts as offset paging. It will be measured with `EXPLAIN ANALYZE` on a seeded
large dataset; if offset paging degrades, that measurement and the fix are documented rather than
pre-emptively optimised.

### 9.1 Frontend scope

React 19 + TypeScript + Vite + Tailwind, complete flows and no decoration:

| Screen | Contents |
|---|---|
| Register / Login | Access token in memory, refresh token handled by the client; reuse the SlangWord approach |
| Wallet | Balance, account id, deposit and withdrawal forms |
| Transfer | Recipient, amount, description |
| Statement | Table with pagination and a date-range filter; refund button per row |
| AI | One input box: type a request, see the parsed proposal, confirm or reject it |

The client **generates the `Idempotency-Key`** (a UUID created when the form is opened, reused across
retries of that same submission) and clears it only after a successful response. This is the part of the
frontend that actually teaches something: it is what makes a double-click or a timeout retry safe.

---

## 10. Testing

No H2. Every integration test runs on Testcontainers PostgreSQL 16. Testcontainers must be **≥ 1.21.4**;
older versions cannot talk to Docker 29.

| Suite | Contents |
|---|---|
| Unit (Mockito, no Spring) | validation, intent parsing against `FakeLlmClient`, request hashing |
| Integration (Testcontainers PG) | repositories, auth flows, every endpoint through MockMvc |
| Concurrency | `ConcurrentWithdrawalIT` (100 threads, one wallet) · `TransferDeadlockIT` (A→B and B→A, 50 threads each) · `LostUpdateIT` · `IsolationLevelIT` · `IdempotencyConcurrencyIT` · `LedgerInvariantIT` |
| Multi-instance (phase 3) | Not part of `mvn verify`. A script starts `docker compose up --scale api=3`, then runs a JUnit suite against the nginx port: 100 threads through the load balancer hitting 3 separate JVMs; balance still correct; each outbox event published exactly once. A control experiment replaces the DB lock with Java `synchronized` and demonstrates that it fails once there is more than one JVM. Results are recorded in the documentation, and the suite is invoked by a dedicated CI job rather than the default build. |
| Kafka | Testcontainers Kafka: outbox → topic → consumer → `transaction_category` |
| AI guardrails | The six assertions in §8.2 |
| Architecture (ArchUnit) | `ai` must not depend on ledger-write repositories · `service` must not import web types · no `double`/`float` anywhere in main sources |

Coverage gate: **85% instruction, 75% branch**, measured across unit + integration together, as in
SlangWord. Branch is raised from SlangWord's 70% because this codebase has substantially more branching
(retry loops, idempotency states, refund rules, LLM failure modes).

---

## 11. Infrastructure

All ports come from `.env`; none collide with the 52 containers already running on the workstation.

| Variable | Default | Service |
|---|---|---|
| `WEB_PORT` | 8095 | nginx |
| `API_PORT` | 8091 | Spring Boot |
| `DB_PORT` | 55433 | PostgreSQL |
| `KAFKA_PORT` | 19092 | Kafka external listener |
| `OLLAMA_PORT` | 11434 | Ollama |

Secrets (`APP_JWT_SECRET`, `GOOGLE_AI_API_KEY`, `POSTGRES_PASSWORD`) have **no default values**.
`@Validated` configuration properties make a missing secret fail at startup with the variable name in the
message. This is SlangWord bug 10.6, not repeated.

Maven runs in a container (the workstation has no JDK and no Maven), invoked from **PowerShell**, because
Git Bash rewrites `/app` into a Windows path:

```
docker run --rm -v "D:/wallet-ledger/backend:/app" -v "wallet-m2:/root/.m2" `
  -v "//var/run/docker.sock:/var/run/docker.sock" `
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true `
  --add-host host.docker.internal:host-gateway -w /app `
  maven:3.9-eclipse-temurin-21 mvn -B verify
```

### Deployment

**Primary — Oracle Cloud Always Free VM (2 OCPU / 12 GB), running the same `docker compose`.**
Keeps Kafka, needs no architectural change, free indefinitely.
Costs to accept: a card is required for identity verification; the VM is ARM64, so CI must build
`linux/arm64` images; ARM capacity is region-limited; and Oracle halved this tier on 2026-06-15 without
announcement, so the allowance may shrink again.

**Fallback — Cloudflare Pages + Render (free web service) + Neon (free PostgreSQL), with Kafka disabled**
via `LoggingEventPublisher`. Render's free PostgreSQL expires after 30 days and is therefore not used.
No free managed Kafka exists in 2026, so the fallback deployment documents that limitation rather than
hiding it; the Kafka path remains proven by Testcontainers in CI.

The `EventPublisher` interface exists specifically so this fallback is a configuration change, not a code
change.

---

## 12. Verified facts

Checked on 2026-09-08. Recorded because the previous project lost time pinning versions from memory.

| Fact | Source |
|---|---|
| Spring AI **2.0.x requires Spring Boot 4.0/4.1** and cannot load in a Boot 3.x context | spring-projects/spring-ai#3379, Spring AI reference |
| Spring AI **1.1.x** is the Boot 3.5-compatible line and **does** ship `spring-ai-starter-model-google-genai` with `spring.ai.google.genai.api-key` | Spring AI 1.1 reference, Google GenAI Chat page |
| Oracle Always Free ARM cut from 4 OCPU/24 GB to **2 OCPU/12 GB on 2026-06-15**, unannounced | InfoQ, 2026-07 |
| **No perpetual free managed Kafka in 2026** — Upstash Kafka discontinued 2025-03-11; Redpanda Serverless is trial-then-paid; Confluent gives $400 for 30 days | Upstash, Redpanda, Confluent |
| Neon and Supabase have permanent free PostgreSQL tiers; **Render's free PostgreSQL expires after 30 days** | Render, Koyeb comparison |

Still to verify during planning, before pinning:

- Exact latest Spring AI 1.1.x patch version on Maven Central, and its declared Spring Boot 3.5 compatibility
- Exact latest Spring Boot 3.5.x patch version
- Testcontainers version (must be ≥ 1.21.4)
- That `gemini-2.5-flash-lite` and `gemini-3.1-flash-lite` are both live model ids, via ListModels
- ARM64 image availability for every compose service

---

## 13. Phases

Each phase ends with a verifiable milestone and a commit the repository owner runs themselves.

| Phase | Scope | Milestone |
|---|---|---|
| 0 | Repo scaffolding, `git init`, Docker Compose skeleton, Flyway V1 | `mvn -B verify` green on an empty app |
| 1 | Auth (access + rotating refresh + reuse detection), wallet, deposit/withdraw/transfer, idempotency, four mutators, audit `REQUIRES_NEW`, all concurrency and isolation tests | 100 concurrent withdrawals leave a correct, non-negative balance; `SUM(amount) = 0` |
| 2 | Statement (paging, date filter, `EXPLAIN ANALYZE`), refund, outbox + relay with `SKIP LOCKED`, Kafka, consumer, notifications | An event travels end-to-end exactly once |
| 3 | React 19 SPA, nginx, GitHub Actions (secret scan → backend → frontend → multi-arch images), `--scale api=3`, deploy to Oracle | Live URL; `MultiInstanceIT` green |
| 4 | Spring AI + Gemini, `ai_intent` + confirm flow, categorisation consumer, NL statement query, injection defences, tier-1 and tier-2 evaluation, AI documentation | Guardrail suite green; accuracy × cost table for both models |
| 5 (optional) | Anomaly detection (rules and statistics, LLM explains only), MCP server exposing read-only wallet tools | — |

---

## 14. Documentation

`docs/hoc/`, in Vietnamese, in the style of `D:\SlangWord\docs\hoc\`: every example is real code with a
path, every decision states why and why not the alternative, and every document ends with a
**"Nếu bị hỏi"** section containing real interview questions.

1. `KIEN_TRUC_VA_QUYET_DINH.md` — architecture and decisions, including a running log of real bugs
   (symptom → wrong hypothesis → why it was wrong → correct fix)
2. `HOC_TRANSACTION_VA_ISOLATION.md`
3. `HOC_KHOA_VA_DONG_THOI.md` — with the measured comparison table
4. `HOC_KE_TOAN_KEP.md`
5. `HOC_IDEMPOTENCY.md`
6. `HOC_OUTBOX_VA_KAFKA.md`
7. `HOC_AI_TRONG_HE_THONG_TIEN.md`
8. `HOC_PROMPT_INJECTION_THUC_CHIEN.md`
9. `HOC_EVAL_VA_CHI_PHI_LLM.md`
10. `BAN_DO_PORTFOLIO.md` — how SlangWord, eda-kafka-lab, travel-ai-agent and this project fit together,
    and which project to talk about for which interview question
11. `README.md` — index, plus a table pointing to existing documents in `D:\SlangWord\docs\hoc\` and
    `D:\langgraph-agent-lab\docs\hoc\` for foundations already covered (Spring Boot, JPA, REST, Security,
    Testing, CI/CD, SQL, Docker, Git, Kafka, RAG, agent patterns, LLM fundamentals). Those are not
    rewritten; each pointer notes how the topic differs in this project.

`HANDOFF_CONTEXT.md` is written last and is gitignored.

---

## 15. Open risks

| Risk | Mitigation |
|---|---|
| Oracle ARM capacity unavailable in the chosen region | Fallback deployment is designed in from the start, not retrofitted |
| Spring AI 1.1.x turns out not to support Boot 3.5 cleanly | Fall back to a hand-written `RestClient` implementation of `LlmClient`; the interface already isolates this |
| The append-only trigger blocks a future migration | Documented; migrations that must touch `ledger_entry` drop and recreate the trigger explicitly |
| Coverage gate of 75% branch proves hard on AI failure paths | Lower the number and document the real one rather than writing tests that only inflate it |
| 52 containers already running; Testcontainers plus Kafka plus Ollama add memory pressure | Ollama is optional and profile-gated; Kafka is single-node KRaft |
