package com.guanxian.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
class PostgresMatchWorkflowIntegrityMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @Test
    void cleanV17UpgradePreservesBusinessRowsAndAddsV18Contract() {
        String schema = "match_v18_clean_upgrade";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        UUID sharePolicyId = jdbc.queryForObject("""
                INSERT INTO association_share_policy(
                  source_association_id,target_association_id,resource_type,
                  visible_fields,created_by_subject)
                VALUES (?,?,'MATCH','["state"]'::jsonb,'association-reviewer')
                RETURNING id
                """, UUID.class, fixture.demandAssociationId(), fixture.candidateAssociationId());

        flyway(schema, null).migrate();

        assertEquals("18", latestVersion(jdbc));
        assertEquals("PENDING_CONFIRMATION", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=?", String.class, fixture.matchId()));
        assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema=?
                   AND ((table_name='negotiation_record' AND column_name='version')
                     OR (table_name='match_feedback' AND column_name IN ('version','updated_at')))
                """, Integer.class, schema));
        String visibleFieldsConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid=c.conrelid
                  JOIN pg_namespace n ON n.oid=t.relnamespace
                 WHERE n.nspname=? AND t.relname='association_share_policy'
                   AND c.conname='association_share_policy_visible_fields_ck'
                """, String.class, schema);
        assertTrue(visibleFieldsConstraint.contains("outcomes"));
        assertEquals(1, jdbc.update("""
                UPDATE association_share_policy
                   SET visible_fields='["state","outcomes"]'::jsonb,
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=?
                """, sharePolicyId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE association_share_policy
                   SET visible_fields='["state","outcomes","internalNotes"]'::jsonb,
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=?
                """, sharePolicyId));
        assertEquals("[\"state\", \"outcomes\"]", jdbc.queryForObject(
                "SELECT visible_fields::text FROM association_share_policy WHERE id=?",
                String.class, sharePolicyId));
        assertEquals(13, jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_trigger t
                  JOIN pg_class c ON c.oid=t.tgrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname=?
                   AND t.tgname LIKE '%v18%'
                   AND NOT t.tgisinternal
                """, Integer.class, schema));
    }

    @Test
    void freshV18DatabaseSupportsTheCompleteLegalWorkflow() {
        String schema = "match_v18_legal_path";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);

        long version = advanceToOutcomePending(schema, fixture);
        insertSuccessFeedback(jdbc, fixture.matchId(), fixture.demandEnterpriseId(), "demand-success");
        insertSuccessFeedback(jdbc, fixture.matchId(), fixture.candidateEnterpriseId(), "candidate-success");

        transaction(schema, tx -> {
            tx.update("""
                    INSERT INTO outcome_archive(
                      match_id,association_id,title,summary,result_type,visibility,archived_by_subject)
                    VALUES (?,?, '已完成成果', '双方确认的试点成果', 'PILOT', 'ASSOCIATION', 'association-reviewer')
                    """, fixture.matchId(), fixture.demandAssociationId());
            assertEquals(1, tx.update("""
                    UPDATE ecosystem_match
                       SET state='ARCHIVED',version=version+1,updated_at=transaction_timestamp()
                     WHERE id=? AND version=? AND state='OUTCOME_PENDING'
                    """, fixture.matchId(), version));
        });

        assertEquals("ARCHIVED", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=?", String.class, fixture.matchId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM match_feedback WHERE match_id=? AND outcome='SUCCESS'",
                Integer.class, fixture.matchId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM outcome_archive WHERE match_id=? AND deleted_at IS NULL",
                Integer.class, fixture.matchId()));
        UUID outcomeId = jdbc.queryForObject(
                "SELECT id FROM outcome_archive WHERE match_id=? AND deleted_at IS NULL",
                UUID.class, fixture.matchId());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE outcome_archive
                   SET archived_by_subject='rewritten-reviewer',version=version+1
                 WHERE id=? AND version=0
                """, outcomeId));
        assertEquals("association-reviewer", jdbc.queryForObject(
                "SELECT archived_by_subject FROM outcome_archive WHERE id=? AND version=0",
                String.class, outcomeId));
    }

    @Test
    void acceptedInvitationMayTerminateBeforeInitialContact() {
        String schema = "match_v18_immediate_termination";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        advanceToConfirmed(schema, fixture);
        createPendingInvitation(schema, fixture, 3);
        acceptInvitation(schema, fixture, 4);

        String terminationReason = "候选方接受后决定不进入初次联系";
        transaction(schema, tx -> {
            tx.update("""
                    INSERT INTO negotiation_record(
                      match_id,association_id,enterprise_id,stage,summary,recorded_by_subject)
                    VALUES (?,?,?,'TERMINATED',?,'candidate-user')
                    """, fixture.matchId(), fixture.candidateAssociationId(),
                    fixture.candidateEnterpriseId(), terminationReason);
            assertEquals(1, tx.update("""
                    UPDATE ecosystem_match
                       SET state='CLOSED',review_status='CLOSED',
                           closed_reason=?,
                           version=version+1,updated_at=transaction_timestamp()
                     WHERE id=? AND version=5 AND state='NEGOTIATING'
                    """, terminationReason, fixture.matchId()));
        });

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=?", String.class, fixture.matchId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM negotiation_record WHERE match_id=? AND stage='TERMINATED'",
                Integer.class, fixture.matchId()));
    }

    @Test
    void sameEnterpriseOnBothSidesStopsV18WithoutRewritingTheRow() {
        String schema = "match_v18_dirty_same_enterprise";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, true);

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "demand owner and candidate enterprise are identical");
        assertFailureContains(failure, fixture.matchId().toString());
        assertEquals("17", latestVersion(jdbc));
        assertEquals(fixture.demandEnterpriseId(), jdbc.queryForObject(
                "SELECT candidate_enterprise_id FROM ecosystem_match WHERE id=?",
                UUID.class, fixture.matchId()));
        assertFalse(columnExists(jdbc, schema, "match_feedback", "version"));
    }

    @Test
    void legacyInvitationEnumRejectedByV18PreflightAndLeftUntouched() {
        String schema = "match_v18_dirty_legacy_enum";
        flyway(schema, MigrationVersion.fromVersion("9")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        UUID invitationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO match_invitation(
                  id,match_id,association_id,sender_enterprise_id,recipient_enterprise_id,
                  invitation_type,status,sent_by_subject)
                VALUES (?,?,?,?,?,'LEGACY_CHANNEL','CANCELLED','legacy-import')
                """, invitationId, fixture.matchId(), fixture.demandAssociationId(),
                fixture.demandEnterpriseId(), fixture.candidateEnterpriseId());

        // V10 intentionally introduced this enum check as NOT VALID, so a row that
        // predates V10 remains present through V17 and must be handled by V18's
        // actionable preflight rather than by a generic VALIDATE error.
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "invitation_type is unsupported");
        assertFailureContains(failure, invitationId.toString());
        assertEquals("17", latestVersion(jdbc));
        assertEquals("LEGACY_CHANNEL", jdbc.queryForObject(
                "SELECT invitation_type FROM match_invitation WHERE id=?",
                String.class, invitationId));
        assertFalse(columnExists(jdbc, schema, "match_feedback", "version"));
    }

    @Test
    void successfulLegacyFeedbackWithCloseReasonStopsV18WithoutRewritingIt() {
        String schema = "match_v18_dirty_success_reason";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        UUID feedbackId = jdbc.queryForObject("""
                INSERT INTO match_feedback(
                  match_id,enterprise_id,rating,outcome,close_reason,comment,submitted_by_subject)
                VALUES (?, ?, 5, 'SUCCESS', '不应出现在成功反馈中的关闭原因',
                        '历史矛盾记录', 'legacy-import')
                RETURNING id
                """, UUID.class, fixture.matchId(), fixture.demandEnterpriseId());

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "successful outcome contains a close reason");
        assertFailureContains(failure, feedbackId.toString());
        assertEquals("17", latestVersion(jdbc));
        assertEquals("不应出现在成功反馈中的关闭原因", jdbc.queryForObject(
                "SELECT close_reason FROM match_feedback WHERE id=?", String.class, feedbackId));
        assertFalse(columnExists(jdbc, schema, "match_feedback", "version"));
    }

    @Test
    void reasonlessRejectedLegacyInvitationStopsV18WithoutInventingAReason() {
        String schema = "match_v18_dirty_rejection_reason";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        seedRecommendedBilateralState(jdbc, fixture, "CLOSED", "候选方拒绝合作");
        UUID invitationId = insertInvitation(
                jdbc, fixture, "REJECTED", "candidate-user", Instant.now());

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "rejected invitation has no response comment");
        assertFailureContains(failure, invitationId.toString());
        assertEquals("17", latestVersion(jdbc));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM match_invitation WHERE id=?", String.class, invitationId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM match_invitation WHERE id=? AND response_comment IS NOT NULL",
                Integer.class, invitationId));
    }

    @Test
    void pendingLegacyInvitationWithResponseTextStopsV18WithoutClearingIt() {
        String schema = "match_v18_dirty_pending_response";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        UUID invitationId = insertInvitation(jdbc, fixture, "PENDING", null, null);
        jdbc.update("UPDATE match_invitation SET response_comment='提前写入的应答' WHERE id=?",
                invitationId);

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "pending or expired invitation contains response text");
        assertFailureContains(failure, invitationId.toString());
        assertEquals("17", latestVersion(jdbc));
        assertEquals("提前写入的应答", jdbc.queryForObject(
                "SELECT response_comment FROM match_invitation WHERE id=?",
                String.class, invitationId));
    }

    @Test
    void advancedParentWithoutAcceptedInvitationStopsV18() {
        String schema = "match_v18_dirty_parent_state";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        seedRecommendedBilateralState(jdbc, fixture, "NEGOTIATING", null);

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "advanced workflow has no accepted invitation");
        assertEquals("17", latestVersion(jdbc));
    }

    @Test
    void pendingInvitationOutsideInvitedStateStopsV18() {
        String schema = "match_v18_dirty_pending_invitation";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        seedRecommendedBilateralState(jdbc, fixture, "CLOSED", "需求方终止撮合");
        insertInvitation(jdbc, fixture, "PENDING", null, null);

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "pending invitation does not match the parent state");
        assertEquals("17", latestVersion(jdbc));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM match_invitation WHERE match_id=?", String.class, fixture.matchId()));
    }

    @Test
    void skippedNegotiationStageStopsV18() {
        String schema = "match_v18_dirty_skipped_negotiation";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        seedRecommendedBilateralState(jdbc, fixture, "NEGOTIATING", null);
        insertInvitation(jdbc, fixture, "ACCEPTED", "candidate-user", Instant.now());
        insertNegotiation(jdbc, fixture, "COMMERCIAL_NEGOTIATION", Instant.now());

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "the first stage is neither INITIAL_CONTACT nor TERMINATED");
        assertEquals("17", latestVersion(jdbc));
    }

    @Test
    void negotiationAfterTerminalStageStopsV18() {
        String schema = "match_v18_dirty_after_terminal";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        seedRecommendedBilateralState(jdbc, fixture, "CLOSED", "洽谈终止");
        insertInvitation(jdbc, fixture, "ACCEPTED", "candidate-user", Instant.now());
        Instant base = Instant.now();
        insertNegotiation(jdbc, fixture, "INITIAL_CONTACT", base);
        insertNegotiation(jdbc, fixture, "TERMINATED", base.plusSeconds(1));
        insertNegotiation(jdbc, fixture, "TECHNICAL_EXCHANGE", base.plusSeconds(2));

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "a record follows a terminal negotiation stage");
        assertEquals("17", latestVersion(jdbc));
    }

    @Test
    void archivedOutcomeWithOnlyOneSuccessStopsV18() {
        String schema = "match_v18_dirty_single_success";
        flyway(schema, MigrationVersion.fromVersion("17")).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        seedRecommendedBilateralState(jdbc, fixture, "ARCHIVED", null);
        insertInvitation(jdbc, fixture, "ACCEPTED", "candidate-user", Instant.now());
        Instant base = Instant.now();
        insertNegotiation(jdbc, fixture, "INITIAL_CONTACT", base);
        insertNegotiation(jdbc, fixture, "TECHNICAL_EXCHANGE", base.plusSeconds(1));
        insertNegotiation(jdbc, fixture, "COMMERCIAL_NEGOTIATION", base.plusSeconds(2));
        insertNegotiation(jdbc, fixture, "CONTRACTING", base.plusSeconds(3));
        insertNegotiation(jdbc, fixture, "CONTRACT_SIGNED", base.plusSeconds(4));
        insertSuccessFeedback(jdbc, fixture.matchId(), fixture.demandEnterpriseId(), "only-one-success");
        jdbc.update("""
                INSERT INTO outcome_archive(
                  match_id,association_id,title,summary,result_type,visibility,archived_by_subject)
                VALUES (?,?, '单方成果', '缺少候选方确认', 'CONTRACT', 'ASSOCIATION', 'legacy-reviewer')
                """, fixture.matchId(), fixture.demandAssociationId());

        FlywayException failure = assertThrows(FlywayException.class, () -> flyway(schema, null).migrate());

        assertFailureContains(failure, "archived match is missing bilateral success");
        assertEquals("17", latestVersion(jdbc));
    }

    @Test
    void runtimeRejectsSkippedStateAndInvalidInvitationRecipient() {
        String schema = "match_v18_runtime_guards";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE ecosystem_match
                   SET state='CONFIRMED',review_status='APPROVED',
                       recommended_by_subject='reviewer',recommended_at=transaction_timestamp(),
                       demand_confirmed_by_subject='demand-user',demand_confirmed_at=transaction_timestamp(),
                       candidate_confirmed_by_subject='candidate-user',candidate_confirmed_at=transaction_timestamp(),
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=?
                """, fixture.matchId()));

        advanceToConfirmed(schema, fixture);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE ecosystem_match
                   SET recommended_by_subject='rewritten-reviewer',
                       recommended_at=transaction_timestamp(),
                       demand_confirmed_by_subject='rewritten-demand-user',
                       demand_confirmed_at=transaction_timestamp(),
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=? AND state='CONFIRMED' AND version=3
                """, fixture.matchId()));
        assertEquals("association-reviewer", jdbc.queryForObject(
                "SELECT recommended_by_subject FROM ecosystem_match WHERE id=? AND version=3",
                String.class, fixture.matchId()));
        UUID outsider = UUID.randomUUID();
        jdbc.update("INSERT INTO enterprise(id,association_id,name,status) VALUES (?,?,?,'ACTIVE')",
                outsider, fixture.candidateAssociationId(), "非候选企业");
        assertThrows(RuntimeException.class, () -> transaction(schema, tx -> {
            tx.update("""
                    UPDATE ecosystem_match
                       SET state='INVITED',version=version+1,updated_at=transaction_timestamp()
                     WHERE id=? AND version=3
                    """, fixture.matchId());
            tx.update("""
                    INSERT INTO match_invitation(
                      match_id,association_id,sender_enterprise_id,recipient_enterprise_id,
                      invitation_type,sent_by_subject)
                    VALUES (?,?,?,?, 'ENTERPRISE','demand-user')
                    """, fixture.matchId(), fixture.demandAssociationId(),
                    fixture.demandEnterpriseId(), outsider);
        }));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=?", String.class, fixture.matchId()));
    }

    @Test
    void runtimeRejectsCloseReasonOnSuccessfulFeedback() {
        String schema = "match_v18_runtime_success_reason";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        advanceToOutcomePending(schema, fixture);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO match_feedback(
                  match_id,enterprise_id,rating,outcome,close_reason,comment,submitted_by_subject)
                VALUES (?, ?, 5, 'SUCCESS', '矛盾的关闭原因', '应被拒绝', 'demand-user')
                """, fixture.matchId(), fixture.demandEnterpriseId()));

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM match_feedback WHERE match_id=?",
                Integer.class, fixture.matchId()));
        insertSuccessFeedback(jdbc, fixture.matchId(), fixture.demandEnterpriseId(), "demand-user");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM match_feedback WHERE match_id=? AND close_reason IS NULL",
                Integer.class, fixture.matchId()));
    }

    @Test
    void runtimeRejectsReasonlessRejectedAndCancelledInvitations() {
        String schema = "match_v18_runtime_invitation_reason";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        advanceToConfirmed(schema, fixture);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO match_invitation(
                  match_id,association_id,sender_enterprise_id,recipient_enterprise_id,
                  invitation_type,status,message,response_comment,sent_by_subject)
                VALUES (?,?,?,?, 'ENTERPRISE','PENDING','邀请正文','尚未应答却提前写入',
                        'demand-user')
                """, fixture.matchId(), fixture.demandAssociationId(),
                fixture.demandEnterpriseId(), fixture.candidateEnterpriseId()));
        createPendingInvitation(schema, fixture, 3);
        UUID invitationId = jdbc.queryForObject(
                "SELECT id FROM match_invitation WHERE match_id=? AND status='PENDING'",
                UUID.class, fixture.matchId());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE match_invitation
                   SET status='REJECTED',responded_by_subject='candidate-user',
                       responded_at=transaction_timestamp(),version=version+1,
                       updated_at=transaction_timestamp()
                 WHERE id=? AND version=0
                """, invitationId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE match_invitation
                   SET status='CANCELLED',version=version+1,updated_at=transaction_timestamp()
                 WHERE id=? AND version=0
                """, invitationId));

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM match_invitation WHERE id=? AND version=0",
                String.class, invitationId));
    }

    @Test
    void invitationResolutionFreezesRequestFactsAndAllTerminalRows() {
        String schema = "match_v18_invitation_immutability";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        advanceToConfirmed(schema, fixture);
        createPendingInvitation(schema, fixture, 3);
        UUID invitationId = jdbc.queryForObject(
                "SELECT id FROM match_invitation WHERE match_id=? AND status='PENDING'",
                UUID.class, fixture.matchId());

        assertThrows(RuntimeException.class, () -> transaction(schema, tx -> {
            tx.update("""
                    UPDATE match_invitation
                       SET status='ACCEPTED',message='篡改后的邀请正文',
                           responded_by_subject='candidate-user',
                           responded_at=transaction_timestamp(),version=version+1,
                           updated_at=transaction_timestamp()
                     WHERE id=? AND status='PENDING' AND version=0
                    """, invitationId);
            tx.update("""
                    UPDATE ecosystem_match
                       SET state='NEGOTIATING',version=version+1,
                           updated_at=transaction_timestamp()
                     WHERE id=? AND version=4
                    """, fixture.matchId());
        }));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM match_invitation WHERE id=? AND version=0",
                String.class, invitationId));

        acceptInvitation(schema, fixture, 4);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE match_invitation
                   SET response_comment='终态后篡改',version=version+1,
                       updated_at=transaction_timestamp()
                 WHERE id=? AND status='ACCEPTED' AND version=1
                """, invitationId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM match_invitation WHERE id=? AND response_comment IS NULL",
                Long.class, invitationId));
    }

    @Test
    void rejectedInvitationAndParentCloseReasonMustAgreeAtomically() {
        String schema = "match_v18_rejection_reason_consistency";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        advanceToConfirmed(schema, fixture);
        createPendingInvitation(schema, fixture, 3);
        UUID invitationId = jdbc.queryForObject(
                "SELECT id FROM match_invitation WHERE match_id=? AND status='PENDING'",
                UUID.class, fixture.matchId());

        assertThrows(RuntimeException.class, () -> transaction(schema, tx -> {
            rejectInvitation(tx, invitationId, "候选方拒绝原因甲");
            closeMatch(tx, fixture.matchId(), 4, "父匹配关闭原因乙");
        }));
        assertEquals("INVITED", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=? AND version=4",
                String.class, fixture.matchId()));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM match_invitation WHERE id=? AND version=0",
                String.class, invitationId));

        transaction(schema, tx -> {
            rejectInvitation(tx, invitationId, "  候选方拒绝合作  ");
            closeMatch(tx, fixture.matchId(), 4, "候选方拒绝合作");
        });
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=? AND closed_reason='候选方拒绝合作'",
                String.class, fixture.matchId()));
    }

    @Test
    void concurrentInvitationAndStaleFeedbackCasAllowOnlyOneWriter() throws Exception {
        String schema = "match_v18_concurrency";
        flyway(schema, null).migrate();
        JdbcTemplate jdbc = jdbc(schema);
        Fixture fixture = seedPendingMatch(jdbc, false);
        advanceToConfirmed(schema, fixture);

        assertConcurrentPendingInvitationUniqueness(schema, fixture);
        acceptInvitation(schema, fixture, 4);
        long outcomeVersion = addNegotiationPath(schema, fixture, 5);
        UUID feedbackId = insertSuccessFeedback(
                jdbc, fixture.matchId(), fixture.demandEnterpriseId(), "initial-feedback");

        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = executor.submit(() -> staleFeedbackUpdate(
                    schema, feedbackId, "first-update", ready, start));
            Future<Integer> second = executor.submit(() -> staleFeedbackUpdate(
                    schema, feedbackId, "second-update", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
        }

        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM match_feedback WHERE id=?", Long.class, feedbackId));
        assertEquals("OUTCOME_PENDING", jdbc.queryForObject(
                "SELECT state FROM ecosystem_match WHERE id=? AND version=?",
                String.class, fixture.matchId(), outcomeVersion));
    }

    private void assertConcurrentPendingInvitationUniqueness(String schema, Fixture fixture) throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                try (Connection connection = connection(schema)) {
                    connection.setAutoCommit(false);
                    update(connection, """
                            UPDATE ecosystem_match
                               SET state='INVITED',version=version+1,updated_at=transaction_timestamp()
                             WHERE id=? AND version=3
                            """, fixture.matchId());
                    insertInvitation(connection, fixture, UUID.randomUUID());
                    firstInserted.countDown();
                    allowCommit.await(10, TimeUnit.SECONDS);
                    connection.commit();
                    return true;
                }
            });
            Future<Boolean> second = executor.submit(() -> {
                assertTrue(firstInserted.await(10, TimeUnit.SECONDS));
                try (Connection connection = connection(schema)) {
                    connection.setAutoCommit(false);
                    try {
                        insertInvitation(connection, fixture, UUID.randomUUID());
                        connection.commit();
                        return true;
                    } catch (SQLException exception) {
                        connection.rollback();
                        return false;
                    } finally {
                        allowCommit.countDown();
                    }
                }
            });
            // The second writer blocks on the parent row/partial unique index until
            // the first commits, so release it after observing that it started.
            Thread.sleep(100);
            allowCommit.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertFalse(second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc(schema).queryForObject(
                "SELECT count(*) FROM match_invitation WHERE match_id=? AND status='PENDING'",
                Integer.class, fixture.matchId()));
    }

    private int staleFeedbackUpdate(
            String schema, UUID feedbackId, String comment,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE match_feedback
                        SET comment=?,submitted_by_subject=?,submitted_at=transaction_timestamp(),
                            updated_at=transaction_timestamp(),version=version+1
                      WHERE id=? AND version=0
                     """)) {
            statement.setString(1, comment);
            statement.setString(2, comment);
            statement.setObject(3, feedbackId);
            return statement.executeUpdate();
        }
    }

    private long advanceToOutcomePending(String schema, Fixture fixture) {
        advanceToConfirmed(schema, fixture);
        createPendingInvitation(schema, fixture, 3);
        acceptInvitation(schema, fixture, 4);
        return addNegotiationPath(schema, fixture, 5);
    }

    private void advanceToConfirmed(String schema, Fixture fixture) {
        transaction(schema, jdbc -> {
            assertEquals(1, jdbc.update("""
                    UPDATE ecosystem_match
                       SET state='RECOMMENDED',review_status='APPROVED',
                           recommended_by_subject='association-reviewer',
                           recommended_at=transaction_timestamp(),
                           version=version+1,updated_at=transaction_timestamp()
                     WHERE id=? AND version=0
                    """, fixture.matchId()));
        });
        transaction(schema, jdbc -> assertEquals(1, jdbc.update("""
                UPDATE ecosystem_match
                   SET state='PARTIALLY_CONFIRMED',
                       demand_confirmed_by_subject='demand-user',
                       demand_confirmed_at=transaction_timestamp(),
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=? AND version=1
                """, fixture.matchId())));
        transaction(schema, jdbc -> assertEquals(1, jdbc.update("""
                UPDATE ecosystem_match
                   SET state='CONFIRMED',
                       candidate_confirmed_by_subject='candidate-user',
                       candidate_confirmed_at=transaction_timestamp(),
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=? AND version=2
                """, fixture.matchId())));
    }

    private void createPendingInvitation(String schema, Fixture fixture, long expectedVersion) {
        transaction(schema, jdbc -> {
            assertEquals(1, jdbc.update("""
                    UPDATE ecosystem_match
                       SET state='INVITED',version=version+1,updated_at=transaction_timestamp()
                     WHERE id=? AND version=?
                    """, fixture.matchId(), expectedVersion));
            insertInvitation(jdbc, fixture, "PENDING", null, null);
        });
    }

    private void acceptInvitation(String schema, Fixture fixture, long expectedMatchVersion) {
        transaction(schema, jdbc -> {
            assertEquals(1, jdbc.update("""
                    UPDATE match_invitation
                       SET status='ACCEPTED',responded_by_subject='candidate-user',
                           responded_at=transaction_timestamp(),version=version+1,
                           updated_at=transaction_timestamp()
                     WHERE match_id=? AND status='PENDING' AND version=0
                    """, fixture.matchId()));
            assertEquals(1, jdbc.update("""
                    UPDATE ecosystem_match
                       SET state='NEGOTIATING',version=version+1,updated_at=transaction_timestamp()
                     WHERE id=? AND version=?
                    """, fixture.matchId(), expectedMatchVersion));
        });
    }

    private void rejectInvitation(JdbcTemplate jdbc, UUID invitationId, String reason) {
        assertEquals(1, jdbc.update("""
                UPDATE match_invitation
                   SET status='REJECTED',response_comment=?,
                       responded_by_subject='candidate-user',responded_at=transaction_timestamp(),
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=? AND status='PENDING' AND version=0
                """, reason, invitationId));
    }

    private void closeMatch(
            JdbcTemplate jdbc, UUID matchId, long expectedVersion, String reason) {
        assertEquals(1, jdbc.update("""
                UPDATE ecosystem_match
                   SET state='CLOSED',review_status='CLOSED',closed_reason=?,
                       version=version+1,updated_at=transaction_timestamp()
                 WHERE id=? AND version=?
                """, reason, matchId, expectedVersion));
    }

    private long addNegotiationPath(String schema, Fixture fixture, long version) {
        String[] stages = {
                "INITIAL_CONTACT", "TECHNICAL_EXCHANGE", "COMMERCIAL_NEGOTIATION",
                "CONTRACTING", "CONTRACT_SIGNED"
        };
        long current = version;
        for (String stage : stages) {
            long expected = current;
            String target = "CONTRACT_SIGNED".equals(stage) ? "OUTCOME_PENDING" : "NEGOTIATING";
            transaction(schema, jdbc -> {
                jdbc.update("""
                        INSERT INTO negotiation_record(
                          match_id,association_id,enterprise_id,stage,summary,recorded_by_subject)
                        VALUES (?,?,?,?,?, 'demand-user')
                        """, fixture.matchId(), fixture.demandAssociationId(),
                        fixture.demandEnterpriseId(), stage, "阶段：" + stage);
                assertEquals(1, jdbc.update("""
                        UPDATE ecosystem_match
                           SET state=?,version=version+1,updated_at=transaction_timestamp()
                         WHERE id=? AND version=?
                        """, target, fixture.matchId(), expected));
            });
            current++;
        }
        return current;
    }

    private Fixture seedPendingMatch(JdbcTemplate jdbc, boolean sameEnterprise) {
        UUID demandAssociation = UUID.randomUUID();
        UUID candidateAssociation = UUID.randomUUID();
        UUID demandEnterprise = UUID.randomUUID();
        UUID candidateEnterprise = sameEnterprise ? demandEnterprise : UUID.randomUUID();
        UUID demand = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        jdbc.update("INSERT INTO association(id,name,status) VALUES (?,?, 'ACTIVE'),(?,?, 'ACTIVE')",
                demandAssociation, "需求协会-" + demandAssociation,
                candidateAssociation, "候选协会-" + candidateAssociation);
        jdbc.update("INSERT INTO enterprise(id,association_id,name,status) VALUES (?,?,?,'ACTIVE')",
                demandEnterprise, demandAssociation, "需求企业-" + demandEnterprise);
        if (!sameEnterprise) {
            jdbc.update("INSERT INTO enterprise(id,association_id,name,status) VALUES (?,?,?,'ACTIVE')",
                    candidateEnterprise, candidateAssociation, "候选企业-" + candidateEnterprise);
        }
        jdbc.update("""
                INSERT INTO cooperation_demand(id,enterprise_id,title,description,status)
                VALUES (?,?,'V18闭环需求','V18迁移和业务闭环验证','OPEN')
                """, demand, demandEnterprise);
        jdbc.update("""
                INSERT INTO ecosystem_match(
                  id,demand_id,candidate_enterprise_id,score,explanation,review_status,state,version)
                VALUES (?,?,?,90,'{}'::jsonb,'PENDING','PENDING_CONFIRMATION',0)
                """, match, demand, candidateEnterprise);
        return new Fixture(match, demandAssociation, candidateAssociation,
                demandEnterprise, candidateEnterprise);
    }

    private void seedRecommendedBilateralState(
            JdbcTemplate jdbc, Fixture fixture, String state, String closeReason) {
        jdbc.update("""
                UPDATE ecosystem_match
                   SET state=?,review_status=?,recommended_by_subject='legacy-reviewer',
                       recommended_at=transaction_timestamp()-interval '3 minutes',
                       demand_confirmed_by_subject='legacy-demand',
                       demand_confirmed_at=transaction_timestamp()-interval '2 minutes',
                       candidate_confirmed_by_subject='legacy-candidate',
                       candidate_confirmed_at=transaction_timestamp()-interval '1 minute',
                       closed_reason=?,version=3,updated_at=transaction_timestamp()
                 WHERE id=?
                """, state, "CLOSED".equals(state) ? "CLOSED" : "APPROVED",
                closeReason, fixture.matchId());
    }

    private UUID insertInvitation(
            JdbcTemplate jdbc, Fixture fixture, String status,
            String respondedBy, Instant respondedAt) {
        UUID invitation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO match_invitation(
                  id,match_id,association_id,sender_enterprise_id,recipient_enterprise_id,
                  invitation_type,status,sent_by_subject,responded_by_subject,responded_at,created_at)
                VALUES (?,?,?,?,?,'ENTERPRISE',?,'demand-user',?,?,
                        transaction_timestamp()-interval '5 minutes')
                """, invitation, fixture.matchId(), fixture.demandAssociationId(),
                fixture.demandEnterpriseId(), fixture.candidateEnterpriseId(), status,
                respondedBy, respondedAt == null ? null : Timestamp.from(respondedAt));
        return invitation;
    }

    private void insertInvitation(Connection connection, Fixture fixture, UUID invitationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO match_invitation(
                  id,match_id,association_id,sender_enterprise_id,recipient_enterprise_id,
                  invitation_type,status,sent_by_subject)
                VALUES (?,?,?,?,?,'ENTERPRISE','PENDING','demand-user')
                """)) {
            statement.setObject(1, invitationId);
            statement.setObject(2, fixture.matchId());
            statement.setObject(3, fixture.demandAssociationId());
            statement.setObject(4, fixture.demandEnterpriseId());
            statement.setObject(5, fixture.candidateEnterpriseId());
            statement.executeUpdate();
        }
    }

    private void insertNegotiation(
            JdbcTemplate jdbc, Fixture fixture, String stage, Instant createdAt) {
        jdbc.update("""
                INSERT INTO negotiation_record(
                  match_id,association_id,enterprise_id,stage,summary,recorded_by_subject,created_at)
                VALUES (?,?,?,?,?,'legacy-user',?)
                """, fixture.matchId(), fixture.demandAssociationId(),
                fixture.demandEnterpriseId(), stage, "历史阶段：" + stage,
                Timestamp.from(createdAt));
    }

    private UUID insertSuccessFeedback(
            JdbcTemplate jdbc, UUID matchId, UUID enterpriseId, String subject) {
        return jdbc.queryForObject("""
                INSERT INTO match_feedback(
                  match_id,enterprise_id,rating,outcome,comment,submitted_by_subject)
                VALUES (?,?,5,'SUCCESS','已确认合作',?) RETURNING id
                """, UUID.class, matchId, enterpriseId, subject);
    }

    private void transaction(String schema, java.util.function.Consumer<JdbcTemplate> work) {
        DataSource dataSource = dataSource(schema);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> work.accept(new JdbcTemplate(dataSource)));
    }

    private int update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement.executeUpdate();
        }
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(dataSource(schema));
    }

    private DataSource dataSource(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        dataSource.setUrl(POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema);
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
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

    private String latestVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    private boolean columnExists(JdbcTemplate jdbc, String schema, String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM information_schema.columns
                   WHERE table_schema=? AND table_name=? AND column_name=?)
                """, Boolean.class, schema, table, column));
    }

    private void assertFailureContains(Throwable throwable, String expected) {
        StringBuilder messages = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            messages.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        assertTrue(messages.toString().toLowerCase(Locale.ROOT)
                        .contains(expected.toLowerCase(Locale.ROOT)),
                () -> "expected migration failure to contain '" + expected + "' but was:\n" + messages);
    }

    private record Fixture(
            UUID matchId,
            UUID demandAssociationId,
            UUID candidateAssociationId,
            UUID demandEnterpriseId,
            UUID candidateEnterpriseId) {
    }
}
