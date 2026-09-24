package com.walletledger.multiinstance;

import com.walletledger.multiinstance.StackClient.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec section 10, multi-instance row, and the Phase 3 milestone. 100 withdrawals of 1.0000
 * against a wallet holding 50.0000, through nginx, spread over three API JVMs sharing one
 * database and one Kafka. Run by scripts/multi-instance.sh, never by the default mvn verify.
 */
@Tag("multi-instance")
class MultiInstanceIT {

    private final StackClient stack = new StackClient();

    @Test
    void threeJvmsKeepTheBalanceExactAndPublishEveryEventExactlyOnce() throws Exception {
        assertThat(System.getenv("MI_MUTATOR")).as("stack must run the production strategy").isEqualTo("pessimistic");
        String username = "mi-" + UUID.randomUUID();
        String token = stack.signUp(username);
        Outcome deposit = stack.post("/api/v1/wallet/deposits", token, "{\"amount\":\"50.0000\"}");
        assertThat(deposit.status()).isEqualTo(201);

        List<Outcome> outcomes = stack.concurrentWithdrawals(token, 100, "1.0000");

        Map<Integer, Long> byStatus = outcomes.stream()
                .collect(Collectors.groupingBy(Outcome::status, Collectors.counting()));
        Set<String> replicas = outcomes.stream().map(Outcome::upstream).collect(Collectors.toSet());
        System.out.printf("pessimistic x3: statuses=%s replicas=%s%n", byStatus, replicas);

        assertThat(replicas).as("nginx spread the load over three JVMs").hasSize(3);
        assertThat(byStatus).containsOnlyKeys(201, 409);
        assertThat(byStatus.get(201)).isEqualTo(50L);
        assertThat(stack.get("/api/v1/wallet", token).get("balance").asText()).isEqualTo("0.0000");
        BigDecimal[] balances = stack.cachedAndDerivedBalance(username);
        assertThat(balances[0]).isEqualByComparingTo(balances[1]).isEqualByComparingTo("0");

        List<String> publicIds = new ArrayList<>();
        publicIds.add(deposit.transactionId());
        outcomes.stream().filter(o -> o.status() == 201).forEach(o -> publicIds.add(o.transactionId()));
        Set<String> keys = stack.kafkaKeysFor(publicIds);
        assertThat(keys).hasSize(51);

        StackClient.awaitUntil(() -> stack.unpublishedOutboxRows(keys) == 0, 60);
        Map<String, Integer> counts = stack.topicCounts(keys, Duration.ofSeconds(5));
        System.out.printf("events on topic: %d distinct keys, max copies of one key=%d%n",
                counts.size(), counts.values().stream().mapToInt(Integer::intValue).max().orElse(0));

        assertThat(counts.keySet()).as("no event lost").containsExactlyInAnyOrderElementsOf(keys);
        assertThat(counts.values()).as("no event published twice").allMatch(copies -> copies == 1);
    }
}
