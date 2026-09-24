package com.walletledger.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Split from OutboxRelay so the transactional claim-publish-mark logic stays directly testable
 * (OutboxRelayIT calls relay.relay() itself) without every test in the shared Spring context
 * also getting a live background scheduler ticking against it. app.outbox.relay.enabled is
 * false by default for exactly that reason; docker-compose (Task 8) turns it on for real
 * deployment.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.fixed-delay:PT5S}")
    public void tick() {
        relay.relay();
    }
}
