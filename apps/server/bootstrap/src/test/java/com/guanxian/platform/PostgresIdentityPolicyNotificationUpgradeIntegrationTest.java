package com.guanxian.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class PostgresIdentityPolicyNotificationUpgradeIntegrationTest {
    private static final UUID ASSOCIATION_A =
            UUID.fromString("78000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B =
            UUID.fromString("78000000-0000-0000-0000-000000000002");
    private static final UUID ENTERPRISE =
            UUID.fromString("78000000-0000-0000-0000-000000000003");
    private static final UUID TENANT_USER =
            UUID.fromString("78000000-0000-0000-0000-000000000004");
    private static final UUID GLOBAL_USER =
            UUID.fromString("78000000-0000-0000-0000-000000000005");
    private static final UUID ORPHAN_POLICY =
            UUID.fromString("78000000-0000-0000-0000-000000000006");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @Test
    void upgradesRealV12RowsWithoutLeavingUnsafeIdentityOrSubscriptionState() {
        flyway(MigrationVersion.fromVersion("12")).migrate();
        JdbcTemplate jdbc = jdbc();

        jdbc.update("INSERT INTO association (id, name) VALUES (?, ?), (?, ?)",
                ASSOCIATION_A, "升级测试协会甲", ASSOCIATION_B, "升级测试协会乙");
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, ?, '测试企业', 'ACTIVE')
                """, ENTERPRISE, ASSOCIATION_A, "升级测试企业");
        jdbc.update("""
                INSERT INTO user_account
                  (id, enterprise_id, association_id, username, display_name,
                   external_subject, status)
                VALUES (?, ?, ?, 'legacy-tenant', '存量租户用户', 'legacy-tenant-subject', 'LOCKED'),
                       (?, NULL, NULL, 'legacy-global', '存量全局用户',
                        'legacy-global-subject', 'ACTIVE')
                """, TENANT_USER, ENTERPRISE, ASSOCIATION_A, GLOBAL_USER);
        jdbc.update("""
                INSERT INTO policy_document (id, title, association_id, status)
                VALUES (?, '待归属的存量政策', NULL, 'DRAFT')
                """, ORPHAN_POLICY);
        jdbc.update("""
                INSERT INTO notification_subscription
                  (user_id, association_id, subscription_type, status)
                VALUES (?, NULL, 'POLICY', 'ACTIVE'), (?, NULL, 'POLICY', 'ACTIVE')
                """, TENANT_USER, GLOBAL_USER);
        jdbc.update("""
                UPDATE notification_subscription
                   SET filters = '{"level":"CITY"}'::jsonb
                 WHERE user_id = ? AND subscription_type = 'POLICY'
                """, TENANT_USER);
        jdbc.update("""
                INSERT INTO notification_message
                  (user_id, notification_type, title, body, status, idempotency_key)
                VALUES (?, 'POLICY', '存量未知状态', '升级时应隔离', 'LEGACY_UNKNOWN', 'legacy-status')
                """, GLOBAL_USER);

        flyway(null).migrate();

        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM user_account WHERE id=?", String.class, TENANT_USER));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM user_account WHERE id=?", Long.class, TENANT_USER));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM revoked_identity_subject", Integer.class));

        assertEquals(ASSOCIATION_A, jdbc.queryForObject("""
                SELECT association_id FROM notification_subscription
                 WHERE user_id=? AND subscription_type='POLICY'
                """, UUID.class, TENANT_USER));
        assertEquals("INACTIVE", jdbc.queryForObject("""
                SELECT status FROM notification_subscription
                 WHERE user_id=? AND subscription_type='POLICY'
                """, String.class, TENANT_USER));
        assertEquals("INACTIVE", jdbc.queryForObject("""
                SELECT status FROM notification_subscription
                 WHERE user_id=? AND subscription_type='POLICY'
                """, String.class, GLOBAL_USER));
        jdbc.update("""
                INSERT INTO notification_subscription
                  (user_id, association_id, subscription_type, status)
                VALUES (?, ?, 'POLICY', 'ACTIVE')
                """, TENANT_USER, ASSOCIATION_B);
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM notification_subscription
                 WHERE user_id=? AND subscription_type='POLICY'
                """, Integer.class, TENANT_USER));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO notification_subscription
                  (user_id, association_id, subscription_type, filters, channels, status)
                VALUES (?, ?, 'POLICY', '{"level":"CITY"}'::jsonb, '["IN_APP"]'::jsonb, 'ACTIVE')
                """, GLOBAL_USER, ASSOCIATION_A));
        assertEquals("FAILED", jdbc.queryForObject("""
                SELECT status FROM notification_message WHERE idempotency_key='legacy-status'
                """, String.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO notification_message
                  (user_id, notification_type, title, body, status, idempotency_key)
                VALUES (?, 'POLICY', '非法状态', '约束应阻止', 'UNKNOWN', 'invalid-status')
                """, GLOBAL_USER));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM policy_document WHERE id=? AND association_id IS NULL",
                Integer.class, ORPHAN_POLICY));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO policy_document (title, association_id, status)
                VALUES ('新的无归属政策', NULL, 'DRAFT')
                """));
        jdbc.update("UPDATE policy_document SET association_id=? WHERE id=?",
                ASSOCIATION_A, ORPHAN_POLICY);
        assertEquals(ASSOCIATION_A, jdbc.queryForObject(
                "SELECT association_id FROM policy_document WHERE id=?",
                UUID.class, ORPHAN_POLICY));
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
