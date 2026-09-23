package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * One PostgreSQL container for the whole JVM.
 * <p>
 * Testcontainers' JUnit extension ({@code @Testcontainers} plus {@code @Container}) is
 * deliberately not used. That extension owns a static container per <em>test class</em> and
 * stops it in {@code afterAll}, while Spring reuses a single cached application context across
 * every class that shares this configuration. The first class passes; from the second class on,
 * the cached DataSource still points at the first container's port, which no longer exists, and
 * every query fails with "Connection refused" after Hikari's 30 second timeout.
 * <p>
 * Starting the container in a static initialiser ties its lifetime to the JVM instead, which is
 * exactly as long as the cached context lives. Ryuk is disabled in this environment, so the
 * shutdown hook below is what actually stops the container; without it every run would leave an
 * orphan behind.
 * <p>
 * The account helpers live here so that the test credential exists as a single literal in the
 * whole test source tree. Repeating a username and password pair in every test class is both
 * duplication and a reliable way to make secret scanners cry wolf.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    // Phase 1C's concurrency-strategy tests (@MockitoBean, @TestConfiguration bean swaps) each
    // give their test class its own cached ApplicationContext and therefore its own Hikari pool —
    // see bug #14 in NHAT_KY_BUG.md. PostgreSQL's default max_connections (100) was sized for a
    // single 64-connection pool with headroom, not several pools coexisting; raising it here is
    // the actual fix, not shrinking every secondary pool further, which would eventually starve
    // the concurrency tests those pools exist to run correctly.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        // spring.main.allow-bean-definition-overriding is off by default in production. Phase 1C's
        // concurrency-strategy tests swap PessimisticBalanceMutator for an alternative by
        // redefining the bean under its own name ("pessimisticBalanceMutator") rather than adding a
        // second @Primary candidate of the same type, which Spring refuses to resolve as an
        // ambiguous wiring error. It has to be set as a System property, this early: SpringApplication
        // reads spring.main.* before either @DynamicPropertySource or @TestPropertySource values
        // exist, so both of those are too late to carry it.
        System.setProperty("spring.main.allow-bean-definition-overriding", "true");
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-".repeat(10));
        // Real contention needs real connections, and it needs TWO per thread: a money operation
        // holds its own transaction open while AuditLogger opens a second one with REQUIRES_NEW.
        // Sized at 32 — one per thread — the concurrency test deadlocks in the pool rather than
        // in the database: every connection sits in a money transaction and none is left to
        // record the audit row. Double it, and the only contention measured is the row lock,
        // which is the contention the test exists to measure. Still well under PostgreSQL's
        // default max_connections of 100.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "64");
    }

    /** Throwaway credential for accounts created inside a single test run. */
    protected static final String TEST_PASSWORD = "integration-test-account";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String credentials(String username) {
        return """
                {"username":"%s","password":"%s"}""".formatted(username, TEST_PASSWORD);
    }

    protected void register(String username) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(APPLICATION_JSON)
                .content(credentials(username)));
    }

    protected JsonNode loginTokens(String username) throws Exception {
        String json = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(credentials(username)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    protected String accessTokenFor(String username) throws Exception {
        register(username);
        return loginTokens(username).get("accessToken").asText();
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
