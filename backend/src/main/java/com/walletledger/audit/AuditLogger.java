package com.walletledger.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQUIRES_NEW, in its own bean. The caller's transaction is suspended and this row commits on
 * its own, so a refused operation still leaves a trail after the caller rolls back. Calling a
 * REQUIRES_NEW method on {@code this} would bypass the Spring proxy and silently join the
 * caller's transaction — the exact bug this design exists to avoid.
 * <p>
 * JdbcTemplate rather than an entity: this writes and is never read by the application, so an
 * entity would buy nothing and would join the caller's persistence context.
 */
@Component
public class AuditLogger {

    private final JdbcTemplate jdbc;

    public AuditLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String action, String detail, AuditOutcome outcome) {
        jdbc.update("insert into audit_log (user_id, action, detail, outcome) values (?, ?, ?, ?)",
                userId, action, detail, outcome.name());
    }
}
