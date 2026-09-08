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
     * <p>
     * saveAndFlush, not save: the INSERT has to hit the database now, so a duplicate key surfaces
     * here where the caller can catch it rather than at commit time where it would escape.
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
