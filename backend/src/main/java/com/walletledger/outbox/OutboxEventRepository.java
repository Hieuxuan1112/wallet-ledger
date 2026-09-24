package com.walletledger.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * FOR UPDATE SKIP LOCKED is what lets several relay instances run concurrently without
     * leader election (spec section 7): each claims a disjoint batch and holds the row locks
     * until its transaction commits, so a second concurrent claim skips straight past them
     * instead of blocking. Native, not JPQL -- Spring Data has no portable SKIP LOCKED syntax,
     * and this is exactly the kind of query whose precise shape decides correctness (see
     * AccountRepository.findByIdForUpdate for the same reasoning applied to balance locking).
     */
    @Query(value = """
            select * from outbox_event
             where published_at is null
             order by id
             for update skip locked
             limit :batchSize
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("batchSize") int batchSize);

    /**
     * Scoped lookup for tests and callers that want "the event(s) for this one transaction," not
     * a claim batch. Once Task 2 wires outbox writes into LedgerPostingService.post(), every test
     * in the whole suite that moves money leaves an unpublished row here (the relay is off by
     * default in tests — see OutboxRelayScheduler). claimBatch(N) only returns the N *oldest*
     * unpublished rows, so once that suite-wide backlog exceeds N, a test's own freshly-written
     * row can silently miss the batch. This method has no such limit, so it is what Task 2's test
     * uses instead of claimBatch to find its own event.
     */
    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, Long aggregateId);
}
