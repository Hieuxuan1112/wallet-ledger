package com.walletledger.idempotency;

import com.walletledger.money.TransactionView;
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
