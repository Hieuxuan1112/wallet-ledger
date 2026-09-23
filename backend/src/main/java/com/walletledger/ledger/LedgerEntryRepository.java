package com.walletledger.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionId(Long transactionId);

    /**
     * A closed projection, not the entity: the statement is a read model joining ledger_entry to
     * ledger_transaction, and Spring Data fills it straight from the query's SELECT list without
     * materialising either entity. Backed by idx_entry_account_created (account_id,
     * created_at DESC, id DESC), which already orders newest-first for the common case of no
     * date filter -- see Task 6 for what EXPLAIN ANALYZE says about it under load.
     */
    interface StatementRow {
        UUID getPublicId();
        TransactionType getType();
        BigDecimal getAmount();
        String getDescription();
        Instant getCreatedAt();
    }

    // Each ":from"/":to"/":type" occurrence becomes its own JDBC placeholder, and the "is null"
    // check on its own gives Postgres nothing to infer a type from -- pgjdbc then rejects the
    // query with "could not determine data type of parameter $N". Casting just that occurrence
    // pins its type; the comparison occurrence already infers one from the column it's next to.
    @Query("""
            select t.publicId as publicId, t.type as type, e.amount as amount,
                   t.description as description, e.createdAt as createdAt
              from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId
             where e.accountId = :accountId
               and (cast(:from as timestamp) is null or e.createdAt >= :from)
               and (cast(:to as timestamp) is null or e.createdAt < :to)
               and (cast(:type as string) is null or t.type = :type)
             order by e.createdAt desc, e.id desc
            """)
    Page<StatementRow> findStatement(@Param("accountId") long accountId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     @Param("type") TransactionType type,
                                     Pageable pageable);
}
