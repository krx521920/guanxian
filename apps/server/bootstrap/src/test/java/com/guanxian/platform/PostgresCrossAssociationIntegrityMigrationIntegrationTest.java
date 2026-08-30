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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresCrossAssociationIntegrityMigrationIntegrationTest {
    private static final UUID ASSOCIATION_A =
            UUID.fromString("7a000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B =
            UUID.fromString("7a000000-0000-0000-0000-000000000002");
    private static final UUID ASSOCIATION_C =
            UUID.fromString("7a000000-0000-0000-0000-000000000003");
    private static final UUID ENTERPRISE_A =
            UUID.fromString("7a000000-0000-0000-0000-000000000004");
    private static final UUID DEMAND_A =
            UUID.fromString("7a000000-0000-0000-0000-000000000005");
    private static final UUID RESOURCE_A =
            UUID.fromString("7a000000-0000-0000-0000-000000000006");
    private static final UUID ENTERPRISE_B =
            UUID.fromString("7a000000-0000-0000-0000-000000000007");
    private static final UUID DEMAND_B =
            UUID.fromString("7a000000-0000-0000-0000-000000000008");
    private static final UUID MATCH_B =
            UUID.fromString("7a000000-0000-0000-0000-000000000009");
    private static final UUID MATCH_WITH_SOURCE_CANDIDATE =
            UUID.fromString("7a000000-0000-0000-0000-00000000000a");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @Test
    void freshDatabaseAppliesV17AndRejectsInvalidCrossAssociationRows() {
        String schema = "cross_association_v17_fresh";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);

        assertEquals("17", latestVersion(jdbc));
        assertEquals("NO", jdbc.queryForObject("""
                SELECT is_nullable
                  FROM information_schema.columns
                 WHERE table_schema=?
                   AND table_name='enterprise_share_consent'
                   AND column_name='resource_id'
                """, String.class, schema));
        assertEquals(13, jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint c
                  JOIN pg_namespace n ON n.oid = c.connamespace
                 WHERE n.nspname = ?
                   AND c.conname IN (
                   'cross_association_recommendation_distinct_participants_ck',
                   'cross_association_recommendation_resource_ck',
                   'cross_association_recommendation_status_ck',
                   'cross_association_recommendation_version_ck',
                   'cross_association_recommendation_review_lifecycle_ck',
                   'association_relationship_suspender_participant_ck',
                   'association_relationship_status_ck',
                   'association_relationship_lifecycle_ck',
                   'association_share_policy_interval_ck',
                   'association_share_policy_status_ck',
                   'association_share_policy_version_ck',
                   'association_share_policy_resource_type_ck',
                   'association_share_policy_visible_fields_ck'
                 )
                   AND c.convalidated
                """, Integer.class, schema));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_constraint c
                  JOIN pg_namespace n ON n.oid = c.connamespace
                 WHERE n.nspname = ?
                   AND c.conname IN (
                     'enterprise_share_consent_status_ck',
                     'enterprise_share_consent_revocation_ck'
                   )
                   AND c.convalidated
                """, Integer.class, schema));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname=?
                   AND indexname='enterprise_share_consent_active_resource_uq'
                """, Integer.class, schema));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject)
                VALUES (?, ?, 'PRODUCT', NULL, 'ACTIVE', 'v17-test')
                """, ENTERPRISE_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject, revoked_at)
                VALUES (?, ?, 'PRODUCT', ?, 'ACTIVE', 'v17-test', now())
                """, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject, revoked_at)
                VALUES (?, ?, 'PRODUCT', ?, 'REVOKED', 'v17-test', NULL)
                """, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject)
                VALUES (?, ?, 'PRODUCT', ?, 'BROKEN', 'v17-test')
                """, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_relationship
                  (source_association_id, target_association_id, status,
                   suspended_by_association_id, suspended_at)
                VALUES (?, ?, 'SUSPENDED', ?, now())
                """, ASSOCIATION_A, ASSOCIATION_B, ASSOCIATION_C));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_relationship
                  (source_association_id, target_association_id, status,
                   suspended_by_association_id, suspended_by_subject, suspended_at)
                VALUES (?, ?, 'ACTIVE', ?, 'invalid-active-suspender', now())
                """, ASSOCIATION_A, ASSOCIATION_B, ASSOCIATION_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_relationship
                  (source_association_id, target_association_id, status, revoked_at)
                VALUES (?, ?, 'REVOKED', now())
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_relationship
                  (source_association_id, target_association_id, status)
                VALUES (?, ?, 'EXPIRED')
                """, ASSOCIATION_A, ASSOCIATION_B));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, 'PENDING_REVIEW', '不能向自身推荐', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_A, DEMAND_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, demand_id, match_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, NULL, NULL, 'PENDING_REVIEW', '缺少业务资源', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, 'BROKEN', '非法状态', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B, DEMAND_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject, version)
                VALUES (?, ?, ?, 'PENDING_REVIEW', '非法版本', 'v17-test', -1)
                """, ASSOCIATION_A, ASSOCIATION_B, DEMAND_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject,
                   reviewed_by_subject, review_comment, reviewed_at)
                VALUES (?, ?, ?, 'PENDING_REVIEW', '待审却已有审核字段', 'v17-test',
                        'reviewer', 'should be absent', now())
                """, ASSOCIATION_A, ASSOCIATION_B, DEMAND_A));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, 'APPROVED', '终态缺少审核人和时间', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B, DEMAND_A));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, valid_from, expires_at,
                   created_by_subject, version)
                VALUES (?, ?, 'PRODUCT', '["name"]'::jsonb, 'ACTIVE', now(),
                        now() - interval '1 minute', 'v17-test', 0)
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject, version)
                VALUES (?, ?, 'SERVICE', '["name"]'::jsonb, 'BROKEN', 'v17-test', 0)
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject, version)
                VALUES (?, ?, 'DEMAND', '["title"]'::jsonb, 'ACTIVE', 'v17-test', -1)
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject)
                VALUES (?, ?, 'SECRET', '["name"]'::jsonb, 'ACTIVE', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject)
                VALUES (?, ?, 'PRODUCT', '[]'::jsonb, 'ACTIVE', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject)
                VALUES (?, ?, 'DEMAND', '["description"]'::jsonb, 'ACTIVE', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject)
                VALUES (?, ?, 'MEMBER', '["name","contactPhone"]'::jsonb, 'ACTIVE', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO association_share_policy
                  (source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject)
                VALUES (?, ?, 'MATCH', '{"state":true}'::jsonb, 'ACTIVE', 'v17-test')
                """, ASSOCIATION_A, ASSOCIATION_B));
    }

    @Test
    void cleanV16UpgradePreservesExpiredRowUntilAtomicRegrantMaterializesIt() {
        String schema = "cross_association_v17_clean_upgrade";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        UUID oldConsent = UUID.fromString("7a100000-0000-0000-0000-000000000001");
        UUID newConsent = UUID.fromString("7a100000-0000-0000-0000-000000000002");
        jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (id, enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject, expires_at)
                VALUES (?, ?, ?, 'PRODUCT', ?, 'ACTIVE', 'legacy-user', now() - interval '1 day')
                """, oldConsent, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A);
        jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (source_association_id, target_association_id, match_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, 'PENDING_REVIEW', 'candidate participant recommendation', 'legacy-user')
                """, ASSOCIATION_A, ASSOCIATION_B, MATCH_WITH_SOURCE_CANDIDATE);

        flyway(schema, null).migrate();

        assertEquals("17", latestVersion(jdbc));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM enterprise_share_consent WHERE id=?",
                String.class, oldConsent),
                "V17 must not silently rewrite legacy business rows during migration");

        jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (id, enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject, expires_at)
                VALUES (?, ?, ?, 'PRODUCT', ?, 'ACTIVE', 'new-user', now() + interval '30 days')
                """, newConsent, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A);

        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM enterprise_share_consent WHERE id=?",
                String.class, oldConsent));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM enterprise_share_consent
                 WHERE enterprise_id=? AND target_association_id=?
                   AND resource_type='PRODUCT' AND resource_id=? AND status='ACTIVE'
                """, Integer.class, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A));
    }

    @Test
    void nullResourceV16DataFailsBeforeSchemaChangesWithActionableHint() {
        String schema = "cross_association_v17_dirty_null_resource";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject)
                VALUES (?, ?, 'PRODUCT', NULL, 'ACTIVE', 'legacy-user')
                """, ENTERPRISE_A, ASSOCIATION_B);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot require enterprise share consent resources");
        assertFailureContains(failure, "Assign each enterprise_share_consent row to the exact resource");
        assertEquals("16", latestVersion(jdbc));
        assertEquals("YES", jdbc.queryForObject("""
                SELECT is_nullable
                  FROM information_schema.columns
                 WHERE table_schema=?
                   AND table_name='enterprise_share_consent'
                   AND column_name='resource_id'
                """, String.class, schema));
    }

    @Test
    void duplicateActiveV16DataFailsBeforeUniqueIndexWithActionableHint() {
        String schema = "cross_association_v17_dirty_duplicate_consent";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject, expires_at)
                VALUES (?, ?, 'PRODUCT', ?, 'ACTIVE', 'legacy-user-1', now() + interval '10 days'),
                       (?, ?, 'PRODUCT', ?, 'ACTIVE', 'legacy-user-2', now() + interval '20 days')
                """, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A,
                ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce unique active enterprise share consent");
        assertFailureContains(failure, "revoke or expire all but the one authorization");
        assertEquals("16", latestVersion(jdbc));
        assertFalse(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM pg_indexes
                   WHERE schemaname=?
                     AND indexname='enterprise_share_consent_active_resource_uq'
                )
                """, Boolean.class, schema));
    }

    @Test
    void inconsistentConsentLifecycleV16DataFailsBeforeConstraintsWithActionableHint() {
        String schema = "cross_association_v17_dirty_consent_lifecycle";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (enterprise_id, target_association_id, resource_type, resource_id,
                   status, granted_by_subject, revoked_at)
                VALUES (?, ?, 'PRODUCT', ?, 'ACTIVE', 'legacy-user', now())
                """, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce enterprise share consent lifecycle");
        assertFailureContains(failure, "ACTIVE must not be revoked");
        assertEquals("16", latestVersion(jdbc));
        assertFalse(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                    FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid = c.connamespace
                   WHERE n.nspname = ?
                     AND c.conname = 'enterprise_share_consent_revocation_ck'
                )
                """, Boolean.class, schema));
    }

    @Test
    void invalidSharePolicyFieldsV16DataFailsBeforeConstraintsWithActionableHint() {
        String schema = "cross_association_v17_dirty_policy_fields";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        UUID policyId = UUID.fromString("7a100000-0000-0000-0000-000000000010");
        jdbc.update("""
                INSERT INTO association_share_policy
                  (id, source_association_id, target_association_id, resource_type,
                   visible_fields, status, created_by_subject)
                VALUES (?, ?, ?, 'PRODUCT', '["name","unsupportedField"]'::jsonb,
                        'ACTIVE', 'legacy-user')
                """, policyId, ASSOCIATION_A, ASSOCIATION_B);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce association share-policy field authorization");
        assertFailureContains(failure, policyId.toString());
        assertFailureContains(failure, "non-empty JSON array containing only its documented visible fields");
        assertEquals("16", latestVersion(jdbc));
        assertFalse(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                    FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid = c.connamespace
                   WHERE n.nspname = ?
                     AND c.conname = 'association_share_policy_visible_fields_ck'
                )
                """, Boolean.class, schema));
    }

    @Test
    void inconsistentRelationshipLifecycleV16DataFailsBeforeConstraintsWithActionableHint() {
        String schema = "cross_association_v17_dirty_relationship_lifecycle";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        jdbc.update("""
                INSERT INTO association_relationship
                  (source_association_id, target_association_id, status,
                   allow_member_data, revoked_at)
                VALUES (?, ?, 'REVOKED', TRUE, now())
                """, ASSOCIATION_A, ASSOCIATION_B);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce association relationship lifecycle");
        assertFailureContains(failure, "keep only the timestamps, actor fields, and reason required by that state");
        assertEquals("16", latestVersion(jdbc));
        assertFalse(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                    FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid = c.connamespace
                   WHERE n.nspname = ?
                     AND c.conname = 'association_relationship_lifecycle_ck'
                )
                """, Boolean.class, schema));
    }

    @Test
    void foreignRecommendationDemandV16DataFailsWithActionableHint() {
        String schema = "cross_association_v17_dirty_recommendation_demand";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        UUID recommendationId = UUID.fromString("7a100000-0000-0000-0000-000000000020");
        jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (id, source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'foreign demand', 'legacy-user')
                """, recommendationId, ASSOCIATION_A, ASSOCIATION_B, DEMAND_B);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce recommendation resource ownership");
        assertFailureContains(failure, recommendationId.toString());
        assertFailureContains(failure, "the demand belongs to the source association");
        assertEquals("16", latestVersion(jdbc));
    }

    @Test
    void foreignRecommendationMatchV16DataFailsWithActionableHint() {
        String schema = "cross_association_v17_dirty_recommendation_match";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        UUID recommendationId = UUID.fromString("7a100000-0000-0000-0000-000000000021");
        jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (id, source_association_id, target_association_id, match_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, ?, 'PENDING_REVIEW', 'foreign match', 'legacy-user')
                """, recommendationId, ASSOCIATION_A, ASSOCIATION_B, MATCH_B);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce recommendation resource ownership");
        assertFailureContains(failure, recommendationId.toString());
        assertFailureContains(failure, "the match contains a source-association participant");
        assertEquals("16", latestVersion(jdbc));
    }

    @Test
    void mismatchedRecommendationDemandAndMatchV16DataFailsWithActionableHint() {
        String schema = "cross_association_v17_dirty_recommendation_mismatch";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        UUID recommendationId = UUID.fromString("7a100000-0000-0000-0000-000000000022");
        jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (id, source_association_id, target_association_id, demand_id, match_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, ?, ?, 'PENDING_REVIEW', 'mismatched resources', 'legacy-user')
                """, recommendationId, ASSOCIATION_A, ASSOCIATION_B,
                DEMAND_A, MATCH_WITH_SOURCE_CANDIDATE);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce recommendation resource ownership");
        assertFailureContains(failure, recommendationId.toString());
        assertFailureContains(failure, "both ids refer to the same demand");
        assertEquals("16", latestVersion(jdbc));
    }

    @Test
    void inconsistentRecommendationReviewLifecycleV16DataFailsWithActionableHint() {
        String schema = "cross_association_v17_dirty_recommendation_review";
        flyway(schema, MigrationVersion.fromVersion("16")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        seedParents(jdbc);
        UUID recommendationId = UUID.fromString("7a100000-0000-0000-0000-000000000023");
        jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (id, source_association_id, target_association_id, demand_id,
                   status, summary, created_by_subject)
                VALUES (?, ?, ?, ?, 'APPROVED', 'missing review provenance', 'legacy-user')
                """, recommendationId, ASSOCIATION_A, ASSOCIATION_B, DEMAND_A);

        FlywayException failure = assertThrows(
                FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "V17 cannot enforce recommendation review lifecycle");
        assertFailureContains(failure, recommendationId.toString());
        assertFailureContains(failure, "approved or rejected rows require a non-empty reviewer subject");
        assertEquals("16", latestVersion(jdbc));
        assertFalse(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                    FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid = c.connamespace
                   WHERE n.nspname = ?
                     AND c.conname = 'cross_association_recommendation_review_lifecycle_ck'
                )
                """, Boolean.class, schema));
    }

    @Test
    void concurrentActiveGrantsForOneResourceAllowExactlyOneCommit() throws Exception {
        String schema = "cross_association_v17_concurrent_consent";
        flyway(schema, null).migrate();
        seedParents(jdbc(schema));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<InsertResult> first = executor.submit(() -> concurrentGrant(
                    schema, UUID.fromString("7a200000-0000-0000-0000-000000000001"), ready, start));
            Future<InsertResult> second = executor.submit(() -> concurrentGrant(
                    schema, UUID.fromString("7a200000-0000-0000-0000-000000000002"), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both grant transactions must be ready");
            start.countDown();

            List<InsertResult> results = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(InsertResult::committed).count());
            InsertResult rejected = results.stream().filter(result -> !result.committed()).findFirst().orElseThrow();
            assertEquals("23505", rejected.sqlState());
            assertTrue(rejected.message().contains("enterprise_share_consent_active_resource_uq"));
            assertEquals(1, jdbc(schema).queryForObject("""
                    SELECT count(*) FROM enterprise_share_consent
                     WHERE enterprise_id=? AND target_association_id=?
                       AND resource_type='PRODUCT' AND resource_id=? AND status='ACTIVE'
                    """, Integer.class, ENTERPRISE_A, ASSOCIATION_B, RESOURCE_A));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private InsertResult concurrentGrant(
            String schema, UUID consentId, CountDownLatch ready, CountDownLatch start) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                schemaUrl(schema), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent consent start latch timed out");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO enterprise_share_consent
                      (id, enterprise_id, target_association_id, resource_type, resource_id,
                       status, granted_by_subject, expires_at)
                    VALUES (?, ?, ?, 'PRODUCT', ?, 'ACTIVE', 'concurrent-user', ?)
                    """)) {
                statement.setObject(1, consentId);
                statement.setObject(2, ENTERPRISE_A);
                statement.setObject(3, ASSOCIATION_B);
                statement.setObject(4, RESOURCE_A);
                statement.setTimestamp(5, Timestamp.from(Instant.now().plusSeconds(3600)));
                try {
                    statement.executeUpdate();
                    connection.commit();
                    return new InsertResult(true, null, "committed");
                } catch (SQLException exception) {
                    connection.rollback();
                    return new InsertResult(false, exception.getSQLState(), exception.getMessage());
                }
            }
        }
    }

    private void seedParents(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO association (id, name, status)
                VALUES (?, 'V17测试协会甲', 'ACTIVE'),
                       (?, 'V17测试协会乙', 'ACTIVE'),
                       (?, 'V17测试协会丙', 'ACTIVE')
                """, ASSOCIATION_A, ASSOCIATION_B, ASSOCIATION_C);
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, 'V17测试企业甲', '测试企业', 'ACTIVE'),
                       (?, ?, 'V17测试企业乙', '测试企业', 'ACTIVE')
                """, ENTERPRISE_A, ASSOCIATION_A, ENTERPRISE_B, ASSOCIATION_B);
        jdbc.update("""
                INSERT INTO cooperation_demand
                  (id, enterprise_id, title, description, status)
                VALUES (?, ?, 'V17测试需求甲', '用于验证推荐约束', 'OPEN'),
                       (?, ?, 'V17测试需求乙', '用于验证推荐约束', 'OPEN')
                """, DEMAND_A, ENTERPRISE_A, DEMAND_B, ENTERPRISE_B);
        jdbc.update("""
                INSERT INTO ecosystem_match
                  (id, demand_id, candidate_enterprise_id, score, explanation, review_status)
                VALUES (?, ?, ?, 80, '{}'::jsonb, 'PENDING'),
                       (?, ?, ?, 82, '{}'::jsonb, 'PENDING')
                """, MATCH_B, DEMAND_B, ENTERPRISE_B,
                MATCH_WITH_SOURCE_CANDIDATE, DEMAND_B, ENTERPRISE_A);
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                schemaUrl(schema), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private String schemaUrl(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema;
    }

    private String latestVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class);
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

    private record InsertResult(boolean committed, String sqlState, String message) {
    }
}
