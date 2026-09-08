package com.walletledger.idempotency;

import com.walletledger.money.TransactionView;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Deliberately not @Transactional. A unique-constraint violation aborts the PostgreSQL
 * transaction, so the losing writer cannot read the winner's row from inside it. Interpreting
 * what the database says is only possible from outside the transaction that failed.
 */
@Service
public class IdempotencyService {

    /** From V4__idempotency_and_audit.sql. */
    private static final String KEY_UNIQUE_CONSTRAINT = "uq_idempotency_user_key";

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
        if (key == null || key.length() < 8 || key.length() > 64) {
            throw new InvalidIdempotencyKeyException();
        }
        String requestHash = codec.hash(requestBody);

        Optional<IdempotencyRecord> existing = records.findByUserIdAndIdemKey(userId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), endpoint, requestHash);
        }
        try {
            return executor.claimAndRun(userId, key, endpoint, requestHash, action);
        } catch (DataIntegrityViolationException e) {
            if (!isKeyCollision(e)) {
                // Some other integrity rule refused the operation — a rejected ledger balance,
                // a violated CHECK. Reporting that as "retry shortly" would be wrong twice: it
                // hides a real failure, and the client takes the advice.
                throw e;
            }
            // Somebody claimed the key between our read and our insert. Their transaction has
            // not committed, so their response is not readable from here, and it must not be
            // guessed.
            throw new IdempotencyInProgressException();
        }
    }

    /**
     * Identified by constraint name rather than by exception class. Through JPA, Hibernate maps
     * every integrity failure — unique violations included — onto the same
     * DataIntegrityViolationException; Spring's DuplicateKeyException only appears on the JDBC
     * path. Catching DuplicateKeyException here would compile, pass a casual reading, and quietly
     * disable idempotency. IdempotencyServiceIT holds that fact in place.
     */
    private boolean isKeyCollision(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && KEY_UNIQUE_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
    }

    /**
     * The endpoint is part of the identity of a request, not decoration. Deposits and withdrawals
     * carry the same AmountRequest, so their canonical JSON is byte-identical and the hash alone
     * cannot tell a deposit from a withdrawal of the same amount. Comparing only the hash let one
     * key replay across endpoints: a withdrawal returned the deposit's stored response, and the
     * money never moved.
     */
    private TransactionView replay(IdempotencyRecord record, String endpoint, String requestHash) {
        if (!record.getEndpoint().equals(endpoint) || !record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        // Defence, not the mechanism. V4's comment presents a null body as how a concurrent
        // duplicate is detected, and that is not what happens: claimAndRun fills the body before
        // it commits, and under READ COMMITTED this query cannot see an uncommitted row at all,
        // so no row reachable from here has a null body. Concurrency is handled entirely by the
        // constraint violation above. This branch stays because decode(null) would be an NPE if a
        // later feature ever does write a half-finished row.
        if (!record.isComplete()) {
            throw new IdempotencyInProgressException();
        }
        return codec.decode(record.getResponseBody());
    }
}
