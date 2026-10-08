package com.uathub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hibernate's ddl-auto=update adds tables and columns but never relaxes NOT NULL. Runs first on every start,
 * outside any transaction, and fixes what older databases need. Each fix is safe to repeat.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaFixes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixes.class);

    private final JdbcTemplate jdbc;

    public SchemaFixes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Attachments can now belong to a scenario step instead of a feedback item.
        apply("ALTER TABLE ATTACHMENT ALTER COLUMN FEEDBACK_ID SET NULL");
    }

    private void apply(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.debug("Schema fix skipped ({}): {}", sql, e.getMessage());
        }
    }
}
