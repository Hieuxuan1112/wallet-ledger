package com.walletledger.multiinstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walletledger.outbox.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Talks to a running docker compose stack the way a client does: over HTTP through nginx. The
 * database and Kafka are read only to check what the HTTP responses cannot show. Addresses are
 * compose service names because scripts/multi-instance.sh runs this JVM on the compose network.
 */
final class StackClient {

    record Outcome(int status, String transactionId, String upstream) {
    }

    private static final String PASSWORD = "multi-instance-account";

    private final String base = env("MI_BASE_URL", "http://web:8080");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    String signUp(String username) throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
        send("POST", "/api/v1/auth/register", null, null, body);
        HttpResponse<String> login = send("POST", "/api/v1/auth/login", null, null, body);
        return json.readTree(login.body()).get("accessToken").asText();
    }

    Outcome post(String path, String token, String body) throws Exception {
        HttpResponse<String> response = send("POST", path, token, UUID.randomUUID().toString(), body);
        String transactionId = response.statusCode() == 201
                ? json.readTree(response.body()).get("transactionId").asText()
                : null;
        return new Outcome(response.statusCode(), transactionId,
                response.headers().firstValue("X-Upstream").orElse(""));
    }

    JsonNode get(String path, String token) throws Exception {
        return json.readTree(send("GET", path, token, null, null).body());
    }

    /** Every request released at once by a start gate, each with its own Idempotency-Key. */
    List<Outcome> concurrentWithdrawals(String token, int threads, String amount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        String body = "{\"amount\":\"%s\",\"description\":\"multi-instance\"}".formatted(amount);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post("/api/v1/wallet/withdrawals", token, body);
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    Connection db() throws Exception {
        return DriverManager.getConnection(
                env("MI_DB_URL", "jdbc:postgresql://db:5432/" + env("POSTGRES_DB", "wallet")),
                env("POSTGRES_USER", "wallet"), System.getenv("POSTGRES_PASSWORD"));
    }

    /** [cached balance, balance derived from ledger_entry] for the wallet of this user. */
    BigDecimal[] cachedAndDerivedBalance(String username) throws Exception {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement("""
                select a.balance, coalesce((select sum(e.amount) from ledger_entry e where e.account_id = a.id), 0)
                  from account a join app_user u on u.id = a.owner_user_id
                 where u.username = ? and a.type = 'USER_WALLET'""")) {
            s.setString(1, username);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return new BigDecimal[]{r.getBigDecimal(1), r.getBigDecimal(2)};
            }
        }
    }

    /** ledger_transaction.id as a string: the key KafkaEventPublisher sends each event under. */
    Set<String> kafkaKeysFor(Collection<String> publicIds) throws Exception {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement(
                "select id from ledger_transaction where public_id = any(?)")) {
            Array ids = c.createArrayOf("uuid", publicIds.stream().map(UUID::fromString).toArray());
            s.setArray(1, ids);
            Set<String> keys = new HashSet<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    keys.add(Long.toString(r.getLong(1)));
                }
            }
            return keys;
        }
    }

    long unpublishedOutboxRows(Set<String> keys) throws Exception {
        try (Connection c = db(); PreparedStatement s = c.prepareStatement(
                "select count(*) from outbox_event where published_at is null and aggregate_id = any(?)")) {
            s.setArray(1, c.createArrayOf("bigint", keys.stream().map(Long::valueOf).toArray()));
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /** How many times each of these keys appears on the topic, reading it from the beginning. */
    Map<String, Integer> topicCounts(Set<String> keys, Duration settle) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, env("MI_KAFKA", "kafka:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "multi-instance-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Map<String, Integer> counts = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props,
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaEventPublisher.TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            long quietUntil = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, quietUntil)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (keys.contains(record.key())) {
                        counts.merge(record.key(), 1, Integer::sum);
                    }
                }
                // Once every key is seen, keep reading a little longer so a duplicate has time to show.
                if (quietUntil == Long.MAX_VALUE && counts.keySet().containsAll(keys)) {
                    quietUntil = System.nanoTime() + settle.toNanos();
                }
            }
        }
        return counts;
    }

    static void awaitUntil(ThrowingCondition condition, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!condition.met()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeoutSeconds + "s");
            }
            Thread.sleep(500);
        }
    }

    interface ThrowingCondition {
        boolean met() throws Exception;
    }

    private HttpResponse<String> send(String method, String path, String token, String key, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
