package com.walletledger.ledger;

import org.postgresql.util.PSQLException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.math.BigDecimal;

/**
 * Isolation.SERIALIZABLE, applied by {@link SerializablePostingGateway} above
 * {@code LedgerPostingService.post}'s own transaction boundary. PostgreSQL implements this as
 * Serializable Snapshot Isolation — it doesn't lock, it detects a dangerous read-write pattern at
 * commit and aborts one side with SQLSTATE 40001. Retry is mandatory here, not optional: the
 * PostgreSQL documentation says applications using SERIALIZABLE must be prepared to retry.
 * <p>
 * Identified by SQLSTATE rather than by exception class on purpose (bug #7's trap, twice
 * removed): Spring's translation of a serialization failure through JPA is not the same
 * well-known type JdbcTemplate would produce, so asserting on a guessed class would be exactly
 * the kind of untested assumption this project has been burned by before.
 */
class SerializableConcurrencyIT extends AbstractConcurrencyContract {

    private static final String SERIALIZATION_FAILURE = "40001";

    @Override
    protected BalanceMutator strategyUnderTest() {
        return selectable.serializable();
    }

    @Override
    protected LedgerTransaction post(TransactionType type, long userId, String description,
                                     long fromId, long toId, BigDecimal amount) {
        for (int attempt = 1; ; attempt++) {
            try {
                return serializableGateway.post(type, userId, description, fromId, toId, amount);
            } catch (RuntimeException e) {
                if (!isSerializationFailure(e) || attempt >= 200) {
                    throw e;
                }
                retries.incrementAndGet();
            }
        }
    }

    private static boolean isSerializationFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql
                    && SERIALIZATION_FAILURE.equals(psql.getSQLState())) {
                return true;
            }
            if (cause instanceof PessimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }
}
