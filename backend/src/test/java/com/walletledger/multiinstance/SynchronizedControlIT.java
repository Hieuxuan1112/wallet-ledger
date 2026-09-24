package com.walletledger.multiinstance;

import com.walletledger.multiinstance.StackClient.Outcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same kind of load as MultiInstanceIT against a stack started with
 * APP_LEDGER_MUTATOR=synchronized: each JVM serialises its own threads with a Java lock, and
 * nothing serialises the three JVMs against each other. Inside one JVM this strategy passed the
 * full contract (SynchronizedConcurrencyIT). Here it must not.
 * <p>
 * The wallet holds far more than the total requested, so every request is one the money covers and
 * anything other than 201 is a wrong rejection. A conflict needs two JVMs to touch the row inside
 * the same few milliseconds, which is likely but not certain in one round of 100 requests, so the
 * test repeats the round (at most five) until it sees one.
 */
@Tag("multi-instance")
class SynchronizedControlIT {

    private static final int MAX_ROUNDS = 5;

    private final StackClient stack = new StackClient();

    @Test
    void aJvmLocalLockDoesNotSerialiseThreeJvms() throws Exception {
        assertThat(System.getenv("MI_MUTATOR")).as("stack must run the control strategy").isEqualTo("synchronized");
        String username = "mi-sync-" + UUID.randomUUID();
        String token = stack.signUp(username);
        assertThat(stack.post("/api/v1/wallet/deposits", token, "{\"amount\":\"50.0000\"}").status()).isEqualTo(201);

        long succeeded = 0;
        long serverErrors = 0;
        long timedOut = 0;
        Map<Integer, Long> statuses = new TreeMap<>();
        for (int round = 1; round <= MAX_ROUNDS && serverErrors == 0; round++) {
            List<Outcome> outcomes = stack.concurrentWithdrawals(token, 100, "0.0100");
            Map<Integer, Long> byStatus = outcomes.stream()
                    .collect(Collectors.groupingBy(Outcome::status, Collectors.counting()));
            byStatus.forEach((status, count) -> statuses.merge(status, count, Long::sum));
            succeeded += byStatus.getOrDefault(201, 0L);
            serverErrors += byStatus.getOrDefault(500, 0L);
            // 504 is nginx giving up on a request the API may still finish afterwards.
            timedOut += byStatus.getOrDefault(504, 0L);
            System.out.printf("synchronized x3, round %d: statuses=%s%n", round, byStatus);
        }
        System.out.printf("synchronized x3, total: statuses=%s%n", statuses);

        // The failure: requests the money covered were rejected because another JVM changed the
        // row underneath a lock this JVM believed was exclusive.
        assertThat(serverErrors).as("version conflicts across JVMs").isPositive();
        // And why it is not worse: @Version (bug #13) still stops a stale write, so no money is
        // created or lost. The wallet was debited once per request that really committed: at least
        // every 201, and at most every 201 plus the 504s, which nginx abandoned but the API may
        // have finished a moment later.
        BigDecimal cent = new BigDecimal("0.0100");
        BigDecimal[] balances = stack.cachedAndDerivedBalance(username);
        assertThat(balances[0]).isEqualByComparingTo(balances[1]);
        BigDecimal debited = new BigDecimal("50.0000").subtract(balances[0]);
        assertThat(debited)
                .isGreaterThanOrEqualTo(cent.multiply(new BigDecimal(succeeded)))
                .isLessThanOrEqualTo(cent.multiply(new BigDecimal(succeeded + timedOut)));
    }
}
