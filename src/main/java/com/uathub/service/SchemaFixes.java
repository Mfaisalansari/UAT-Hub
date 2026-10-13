package com.uathub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // Scenario IDs are now unique per UAT cycle (copies share an ID across cycles), not per project.
        dropUnique("SCENARIO", Set.of("PROJECT_ID", "CODE"));
        // A scenario can now appear once per data set in a run.
        dropUnique("EXECUTION", Set.of("RUN_ID", "SCENARIO_ID"));
    }

    /** Drops a unique constraint by its exact column set; Hibernate gives these generated names. */
    private void dropUnique(String table, Set<String> columns) {
        try {
            List<String> names = jdbc.queryForList("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                    + "WHERE TABLE_NAME = ? AND CONSTRAINT_TYPE = 'UNIQUE'", String.class, table);
            for (String name : names) {
                Set<String> cols = new HashSet<>(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_NAME = ? AND CONSTRAINT_NAME = ?", String.class, table, name));
                if (cols.equals(columns)) {
                    jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
                    log.info("Removed old unique rule {} on {} {}", name, table, columns);
                }
            }
        } catch (Exception e) {
            log.debug("Unique constraint check on {} skipped: {}", table, e.getMessage());
        }
    }

    private void apply(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.debug("Schema fix skipped ({}): {}", sql, e.getMessage());
        }
    }
}
