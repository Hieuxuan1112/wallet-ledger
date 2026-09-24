package com.walletledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository events;
    private final EventPublisher publisher;

    public OutboxRelay(OutboxEventRepository events, EventPublisher publisher) {
        this.events = events;
        this.publisher = publisher;
    }

    /**
     * FOR UPDATE SKIP LOCKED (OutboxEventRepository.claimBatch) holds each claimed row's lock
     * until this transaction commits, so a second replica's concurrent claim can never see the
     * same rows -- see OutboxRelaySkipLockedIT for the proof and MultiInstanceIT (phase 3) for
     * the same property proven across real JVMs. A publish failure is caught per-event so one
     * bad event does not block the batch behind it; its published_at stays null and it is
     * retried on the next tick, with attempts incremented either way.
     */
    @Transactional
    public void relay() {
        for (OutboxEvent event : events.claimBatch(BATCH_SIZE)) {
            event.incrementAttempts();
            try {
                publisher.publish(event);
                event.markPublished();
            } catch (RuntimeException e) {
                log.error("Could not publish outbox event {}", event.getId(), e);
            }
            events.save(event);
        }
    }
}
