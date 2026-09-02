package com.guanxian.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresPolicyImpactAssociationMigrationIntegrationTest {
    private static final UUID ASSOCIATION_A =
            UUID.fromString("79000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B =
            UUID.fromString("79000000-0000-0000-0000-000000000002");
    private static final UUID ENTERPRISE_A =
            UUID.fromString("79000000-0000-0000-0000-000000000003");
    private static final UUID ENTERPRISE_B =
            UUID.fromString("79000000-0000-0000-0000-000000000004");
    private static final UUID POLICY_A =
            UUID.fromString("79000000-0000-0000-0000-000000000005");
    private static final UUID POLICY_B =
            UUID.fromString("79000000-0000-0000-0000-000000000006");
    private static final UUID IMPACT_A =
            UUID.fromString("79000000-0000-0000-0000-000000000007");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @Test
    void freshDatabaseDerivesAssociationAndRejectsCrossAssociationInsertAndUpdate() {
        String schema = "policy_impact_fresh";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);

        jdbc.update("""
                INSERT INTO policy_impact_analysis
                  (id, policy_document_id, enterprise_id, impact_level, summary)
                VALUES (?, ?, ?, 'MEDIUM', '同协会影响分析')
                """, IMPACT_A, POLICY_A, ENTERPRISE_A);

        assertEquals(ASSOCIATION_A, jdbc.queryForObject(
                "SELECT association_id FROM policy_impact_analysis WHERE id=?",
                UUID.class, IMPACT_A));

        DataIntegrityViolationException insertFailure = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        INSERT INTO policy_impact_analysis
                          (policy_document_id, enterprise_id, impact_level, summary)
                        VALUES (?, ?, 'HIGH', '跨协会影响分析')
                        """, POLICY_A, ENTERPRISE_B));
        assertFailureContains(insertFailure, "policy impact association mismatch");

        DataIntegrityViolationException updateFailure = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "UPDATE policy_impact_analysis SET enterprise_id=? WHERE id=?",
                        ENTERPRISE_B, IMPACT_A));
        assertFailureContains(updateFailure, "policy impact association mismatch");

        DataIntegrityViolationException parentMoveFailure = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "UPDATE enterprise SET association_id=? WHERE id=?",
                        ASSOCIATION_B, ENTERPRISE_A));
        assertFailureContains(parentMoveFailure, "policy_impact_enterprise_association_fk");

        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint
                 WHERE conrelid = 'policy_impact_analysis'::regclass
                   AND conname IN (
                     'policy_impact_policy_association_fk',
                     'policy_impact_enterprise_association_fk'
                   )
                   AND convalidated
                """, Integer.class));
    }

    @Test
    void cleanV15DataUpgradesThroughLatestAndBackfillsAssociation() {
        String schema = "policy_impact_clean_upgrade";
        flyway(schema, MigrationVersion.fromVersion("15")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        jdbc.update("""
                INSERT INTO policy_impact_analysis
                  (id, policy_document_id, enterprise_id, impact_level, summary)
                VALUES (?, ?, ?, 'LOW', 'V15存量同协会影响分析')
                """, IMPACT_A, POLICY_A, ENTERPRISE_A);

        flyway(schema, null).migrate();

        assertEquals(ASSOCIATION_A, jdbc.queryForObject(
                "SELECT association_id FROM policy_impact_analysis WHERE id=?",
                UUID.class, IMPACT_A));
        assertEquals("NO", jdbc.queryForObject("""
                SELECT is_nullable
                  FROM information_schema.columns
                 WHERE table_schema=?
                   AND table_name='policy_impact_analysis'
                   AND column_name='association_id'
                """, String.class, schema));
        assertEquals("17", jdbc.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class));
    }

    @Test
    void mismatchedV15DataFailsUpgradeBeforeSchemaChangesWithActionableMessage() {
        String schema = "policy_impact_dirty_upgrade";
        flyway(schema, MigrationVersion.fromVersion("15")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        jdbc.update("""
                INSERT INTO policy_impact_analysis
                  (id, policy_document_id, enterprise_id, impact_level, summary)
                VALUES (?, ?, ?, 'HIGH', 'V15存量跨协会影响分析')
                """, IMPACT_A, POLICY_A, ENTERPRISE_B);

        FlywayException migrationFailure = assertThrows(
                FlywayException.class,
                () -> flyway(schema, null).migrate());

        assertFailureContains(
                migrationFailure,
                "V16 cannot enforce policy impact association consistency: found 1 mismatched row(s)");
        assertFailureContains(migrationFailure, "Correct or delete the mismatched policy_impact_analysis rows");
        assertEquals("15", jdbc.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class));
        assertFalse(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                    FROM information_schema.columns
                   WHERE table_schema=?
                     AND table_name='policy_impact_analysis'
                     AND column_name='association_id'
                )
                """, Boolean.class, schema));
    }

    private void seedParents(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO association (id, name, status)
                VALUES (?, '政策影响测试协会甲', 'ACTIVE'),
                       (?, '政策影响测试协会乙', 'ACTIVE')
                """, ASSOCIATION_A, ASSOCIATION_B);
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, '政策影响测试企业甲', '测试企业', 'ACTIVE'),
                       (?, ?, '政策影响测试企业乙', '测试企业', 'ACTIVE')
                """, ENTERPRISE_A, ASSOCIATION_A, ENTERPRISE_B, ASSOCIATION_B);
        jdbc.update("""
                INSERT INTO policy_document (id, title, association_id, status)
                VALUES (?, '政策影响测试政策甲', ?, 'ACTIVE'),
                       (?, '政策影响测试政策乙', ?, 'ACTIVE')
                """, POLICY_A, ASSOCIATION_A, POLICY_B, ASSOCIATION_B);
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema);
        configuration.target(target == null ? MigrationVersion.fromVersion("17") : target);
        return configuration.load();
    }

    private JdbcTemplate jdbc(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        String url = POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
        return new JdbcTemplate(new DriverManagerDataSource(
                url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private void assertFailureContains(Throwable failure, String expected) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        assertTrue(
                messages.toString().toLowerCase(Locale.ROOT)
                        .contains(expected.toLowerCase(Locale.ROOT)),
                () -> "Expected failure to contain '" + expected + "' but was:\n" + messages);
    }
}
