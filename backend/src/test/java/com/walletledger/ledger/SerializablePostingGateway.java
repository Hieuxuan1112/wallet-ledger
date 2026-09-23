package com.walletledger.ledger;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * {@code LedgerPostingService.post} is {@code @Transactional} with the default isolation, shared
 * by every strategy — it can't declare SERIALIZABLE itself without forcing that isolation level
 * on pessimistic and optimistic too. With REQUIRED propagation, whichever {@code @Transactional}
 * method starts the transaction owns its isolation level; a method it then calls just joins that
 * same transaction. This gateway exists purely to be that outermost, SERIALIZABLE-declaring
 * caller for the serializable strategy's test path.
 */
class SerializablePostingGateway {

    private final LedgerPostingService posting;

    SerializablePostingGateway(LedgerPostingService posting) {
        this.posting = posting;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public LedgerTransaction post(TransactionType type, long initiatedByUserId, String description,
                                  long fromAccountId, long toAccountId, BigDecimal amount) {
        return posting.post(type, initiatedByUserId, description, fromAccountId, toAccountId, amount);
    }
}
