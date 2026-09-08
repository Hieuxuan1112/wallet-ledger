package com.walletledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

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
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-".repeat(10));
    }

    @Autowired
    protected MockMvc mockMvc;
}
