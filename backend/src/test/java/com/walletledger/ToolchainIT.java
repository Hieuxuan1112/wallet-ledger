package com.walletledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ToolchainIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void springContextStartsAgainstARealPostgres() {
        String version = jdbcTemplate.queryForObject("select version()", String.class);
        assertThat(version).contains("PostgreSQL 16");
    }
}
