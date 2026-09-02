package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "guanxian.business.repository",
        havingValue = "postgres",
        matchIfMissing = true)
class PostgresEcosystemWorkflowStore implements EcosystemWorkflowStore {
    private static final String INVITATION_SELECT = """
            SELECT i.id, i.match_id, i.sender_enterprise_id, i.recipient_enterprise_id,
                   i.invitation_type, i.status, i.message, i.response_comment,
                   i.sent_by_subject, i.responded_by_subject, i.expires_at,
                   i.responded_at, i.version, i.created_at, i.updated_at
              FROM match_invitation i
              JOIN ecosystem_match m ON m.id=i.match_id
              JOIN cooperation_demand d ON d.id=m.demand_id
              JOIN enterprise de ON de.id=d.enterprise_id
            """;
    private final NamedParameterJdbcTemplate jdbc;

    PostgresEcosystemWorkflowStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MatchInvitationView createInvitation(
            UUID matchId,
            UUID associationId,
            UUID senderEnterpriseId,
            MatchInvitationRequest request,
            ActorScope actor) {
        requireAssociationArgument(associationId, actor);
        requireMatchWrite(matchId, actor);
        if (!Objects.equals(senderEnterpriseId, actor.enterpriseId())) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "invitation sender is outside the selected enterprise context");
        }
        if (!isCandidate(matchId, request.recipientEnterpriseId())) {
            throw new ForbiddenException(
                    "MATCH_SCOPE_VIOLATION",
                    "invitation recipient must be the candidate enterprise of the match");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId)
                .addValue("senderEnterpriseId", senderEnterpriseId)
                .addValue("recipientEnterpriseId", request.recipientEnterpriseId())
                .addValue("invitationType", request.invitationType())
                .addValue("message", clean(request.message()))
                .addValue("expiresAt", timestamp(request.expiresAt()))
                .addValue("subject", actor.subject());
        UUID id = jdbc.queryForObject("""
                INSERT INTO match_invitation (
                    match_id, association_id, sender_enterprise_id, recipient_enterprise_id,
                    invitation_type, message, sent_by_subject, expires_at)
                VALUES (
                    :matchId, :associationId, :senderEnterpriseId, :recipientEnterpriseId,
                    :invitationType, :message, :subject, :expiresAt)
                RETURNING id
                """, params, UUID.class);
        return findInvitation(id, actor).orElseThrow();
    }

    @Override
    public List<MatchInvitationView> invitations(UUID matchId, ActorScope actor) {
        MapSqlParameterSource params = scopeParams(actor).addValue("matchId", matchId);
        return jdbc.query(INVITATION_SELECT + scope(actor)
                        + " AND i.match_id=:matchId ORDER BY i.created_at DESC, i.id",
                params, this::mapInvitation);
    }

    @Override
    public Optional<MatchInvitationView> findInvitation(UUID invitationId, ActorScope actor) {
        MapSqlParameterSource params = scopeParams(actor).addValue("id", invitationId);
        return jdbc.query(INVITATION_SELECT + scope(actor) + " AND i.id=:id",
                params, this::mapInvitation).stream().findFirst();
    }

    @Override
    public List<MatchInvitationView> expirePendingInvitations(
            UUID matchId, ActorScope actor) {
        requireMatchWrite(matchId, actor);
        return jdbc.query("""
                UPDATE match_invitation
                   SET status='EXPIRED', version=version+1, updated_at=now()
                 WHERE match_id=:matchId
                   AND status='PENDING'
                   AND expires_at IS NOT NULL
                   AND expires_at <= now()
                RETURNING id, match_id, sender_enterprise_id, recipient_enterprise_id,
                          invitation_type, status, message, response_comment,
                          sent_by_subject, responded_by_subject, expires_at,
                          responded_at, version, created_at, updated_at
                """, new MapSqlParameterSource("matchId", matchId), this::mapInvitation);
    }

    @Override
    public List<MatchInvitationView> cancelPendingInvitations(
            UUID matchId, String reason, ActorScope actor) {
        requireMatchWrite(matchId, actor);
        return jdbc.query("""
                UPDATE match_invitation
                   SET status='CANCELLED', response_comment=:reason,
                       version=version+1, updated_at=now()
                 WHERE match_id=:matchId AND status='PENDING'
                RETURNING id, match_id, sender_enterprise_id, recipient_enterprise_id,
                          invitation_type, status, message, response_comment,
                          sent_by_subject, responded_by_subject, expires_at,
                          responded_at, version, created_at, updated_at
                """, new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("reason", clean(reason)), this::mapInvitation);
    }

    @Override
    public boolean hasPendingInvitation(UUID matchId, ActorScope actor) {
        if (!canReadMatch(matchId, actor)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM match_invitation
                     WHERE match_id=:matchId
                       AND status='PENDING'
                       AND (expires_at IS NULL OR expires_at > now()))
                """, new MapSqlParameterSource("matchId", matchId), Boolean.class));
    }

    @Override
    public Optional<MatchInvitationView> respondInvitation(
            UUID invitationId,
            long expectedVersion,
            boolean accepted,
            String comment,
            ActorScope actor) {
        MatchInvitationView existing = findInvitation(invitationId, actor).orElse(null);
        if (existing == null
                || actor.enterpriseId() == null
                || !actor.enterpriseId().equals(existing.recipientEnterpriseId())) {
            return Optional.empty();
        }
        requireMatchWrite(existing.matchId(), actor);
        int updated = jdbc.update("""
                UPDATE match_invitation
                   SET status=:status,
                       response_comment=:comment,
                       responded_by_subject=:subject,
                       responded_at=now(),
                       version=version+1,
                       updated_at=now()
                 WHERE id=:id
                   AND version=:expectedVersion
                   AND status='PENDING'
                   AND (expires_at IS NULL OR expires_at>transaction_timestamp())
                """, new MapSqlParameterSource()
                .addValue("id", invitationId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("status", accepted ? "ACCEPTED" : "REJECTED")
                .addValue("comment", clean(comment))
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : findInvitation(invitationId, actor);
    }

    @Override
    public NegotiationView addNegotiation(
            UUID matchId,
            UUID associationId,
            UUID enterpriseId,
            NegotiationRequest request,
            ActorScope actor) {
        requireAssociationArgument(associationId, actor);
        requireMatchWrite(matchId, actor);
        requireRecorder(matchId, enterpriseId, actor);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId)
                .addValue("enterpriseId", enterpriseId)
                .addValue("stage", request.stage().trim())
                .addValue("summary", request.summary().trim())
                .addValue("nextAction", clean(request.nextAction()))
                .addValue("nextActionAt", timestamp(request.nextActionAt()))
                .addValue("subject", actor.subject());
        UUID id = jdbc.queryForObject("""
                INSERT INTO negotiation_record (
                    match_id, association_id, enterprise_id, stage, summary,
                    next_action, next_action_at, recorded_by_subject)
                VALUES (
                    :matchId, :associationId, :enterpriseId, :stage, :summary,
                    :nextAction, :nextActionAt, :subject)
                RETURNING id
                """, params, UUID.class);
        return jdbc.query("""
                SELECT id, match_id, enterprise_id, stage, summary, next_action,
                       next_action_at, recorded_by_subject, created_at, version
                  FROM negotiation_record
                 WHERE id=:id
                """, new MapSqlParameterSource("id", id), this::mapNegotiation).getFirst();
    }

    @Override
    public List<NegotiationView> negotiations(UUID matchId, ActorScope actor) {
        return jdbc.query("""
                SELECT n.id, n.match_id, n.enterprise_id, n.stage, n.summary, n.next_action,
                       n.next_action_at, n.recorded_by_subject, n.created_at, n.version
                  FROM negotiation_record n
                  JOIN ecosystem_match m ON m.id=n.match_id
                  JOIN cooperation_demand d ON d.id=m.demand_id
                  JOIN enterprise de ON de.id=d.enterprise_id
                """ + matchScope(actor)
                        + " AND n.match_id=:matchId ORDER BY n.created_at DESC, n.id DESC",
                scopeParams(actor).addValue("matchId", matchId), this::mapNegotiation);
    }

    @Override
    public Optional<NegotiationView> latestNegotiation(UUID matchId, ActorScope actor) {
        return negotiations(matchId, actor).stream().findFirst();
    }

    @Override
    public Optional<MatchFeedbackView> feedbackByEnterprise(
            UUID matchId, UUID enterpriseId, ActorScope actor) {
        return feedback(matchId, actor).stream()
                .filter(value -> value.enterpriseId().equals(enterpriseId))
                .findFirst();
    }

    @Override
    public Optional<MatchFeedbackView> upsertFeedback(
            UUID matchId, UUID enterpriseId, Long expectedVersion,
            MatchFeedbackRequest request, ActorScope actor) {
        requireMatchWrite(matchId, actor);
        requireSelectedEnterprise(enterpriseId, actor);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("enterpriseId", enterpriseId)
                .addValue("rating", request.rating())
                .addValue("outcome", request.outcome().trim())
                .addValue("closeReason", clean(request.closeReason()))
                .addValue("comment", clean(request.comment()))
                .addValue("subject", actor.subject())
                .addValue("expectedVersion", expectedVersion);
        List<UUID> ids = expectedVersion == null ? List.of() : jdbc.query("""
                UPDATE match_feedback
                   SET rating=:rating,
                       outcome=:outcome,
                       close_reason=:closeReason,
                       comment=:comment,
                       submitted_by_subject=:subject,
                       submitted_at=now(),
                       version=version+1,
                       updated_at=now()
                 WHERE match_id=:matchId
                   AND enterprise_id=:enterpriseId
                   AND version=:expectedVersion
                RETURNING id
                """, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty() && (expectedVersion == null || expectedVersion == 0)) {
            ids = jdbc.query("""
                    INSERT INTO match_feedback (
                        match_id, enterprise_id, rating, outcome, close_reason,
                        comment, submitted_by_subject)
                    VALUES (
                        :matchId, :enterpriseId, :rating, :outcome, :closeReason,
                        :comment, :subject)
                    ON CONFLICT (match_id, enterprise_id) DO NOTHING
                    RETURNING id
                    """, params, (rs, rowNum) -> rs.getObject("id", UUID.class));
        }
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT id, match_id, enterprise_id, rating, outcome, close_reason,
                       comment, submitted_by_subject, submitted_at, version, updated_at
                  FROM match_feedback
                 WHERE id=:id
                """, new MapSqlParameterSource("id", ids.getFirst()), this::mapFeedback)
                .stream().findFirst();
    }

    @Override
    public List<MatchFeedbackView> feedback(UUID matchId, ActorScope actor) {
        return jdbc.query("""
                SELECT f.id, f.match_id, f.enterprise_id, f.rating, f.outcome, f.close_reason,
                       f.comment, f.submitted_by_subject, f.submitted_at, f.version, f.updated_at
                  FROM match_feedback f
                  JOIN ecosystem_match m ON m.id=f.match_id
                  JOIN cooperation_demand d ON d.id=m.demand_id
                  JOIN enterprise de ON de.id=d.enterprise_id
                """ + matchScope(actor)
                        + " AND f.match_id=:matchId ORDER BY f.submitted_at DESC, f.id",
                scopeParams(actor).addValue("matchId", matchId), this::mapFeedback);
    }

    @Override
    public OutcomeArchiveView archive(
            UUID matchId, UUID associationId, OutcomeArchiveRequest request, ActorScope actor) {
        requireAssociationArgument(associationId, actor);
        requireMatchWrite(matchId, actor);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId)
                .addValue("title", request.title().trim())
                .addValue("summary", request.summary().trim())
                .addValue("contractAmount", request.contractAmount())
                .addValue("resultType", request.resultType().trim())
                .addValue("visibility", request.visibility() == null ? "ASSOCIATION" : request.visibility())
                .addValue("subject", actor.subject());
        UUID id = jdbc.queryForObject("""
                INSERT INTO outcome_archive (
                    match_id, association_id, title, summary, contract_amount,
                    result_type, visibility, archived_by_subject)
                VALUES (
                    :matchId, :associationId, :title, :summary, :contractAmount,
                    :resultType, :visibility, :subject)
                RETURNING id
                """, params, UUID.class);
        return jdbc.query("""
                SELECT id, match_id, title, summary, contract_amount, result_type,
                       visibility, archived_by_subject, archived_at, version
                  FROM outcome_archive
                 WHERE id=:id
                """, new MapSqlParameterSource("id", id), this::mapOutcome).getFirst();
    }

    @Override
    public List<OutcomeArchiveView> outcomes(UUID matchId, ActorScope actor) {
        return jdbc.query("""
                SELECT o.id, o.match_id, o.title, o.summary, o.contract_amount, o.result_type,
                       o.visibility, o.archived_by_subject, o.archived_at, o.version
                  FROM outcome_archive o
                  JOIN ecosystem_match m ON m.id=o.match_id
                  JOIN cooperation_demand d ON d.id=m.demand_id
                  JOIN enterprise de ON de.id=d.enterprise_id
                  JOIN enterprise ce ON ce.id=m.candidate_enterprise_id
                """ + outcomeMatchScope(actor)
                        + " AND o.match_id=:matchId AND o.deleted_at IS NULL"
                        + outcomeVisibilityScope()
                        + " ORDER BY o.archived_at DESC, o.id",
                scopeParams(actor).addValue("matchId", matchId), this::mapOutcome);
    }

    @Override
    public boolean hasActiveOutcome(UUID matchId, ActorScope actor) {
        requireMatchWrite(matchId, actor);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM outcome_archive
                     WHERE match_id=:matchId AND deleted_at IS NULL)
                """, new MapSqlParameterSource("matchId", matchId), Boolean.class));
    }

    private String scope(ActorScope actor) {
        return matchScope(actor);
    }

    private String matchScope(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                return actor.enterpriseId() == null ? " WHERE TRUE" : " WHERE FALSE";
            }
            if (actor.enterpriseId() != null) {
                return " WHERE ((de.association_id=:associationId"
                        + " AND d.enterprise_id=:enterpriseId)"
                        + " OR (m.state<>'PENDING_CONFIRMATION'"
                        + " AND m.candidate_enterprise_id=:enterpriseId"
                        + " AND EXISTS (SELECT 1 FROM enterprise system_candidate"
                        + " WHERE system_candidate.id=m.candidate_enterprise_id"
                        + " AND system_candidate.association_id=:associationId)))";
            }
            return " WHERE (de.association_id=:associationId"
                    + " OR (m.state<>'PENDING_CONFIRMATION'"
                    + " AND EXISTS (SELECT 1 FROM enterprise system_candidate"
                    + " WHERE system_candidate.id=m.candidate_enterprise_id"
                    + " AND system_candidate.association_id=:associationId)))";
        }
        if (actor.isAssociationStaff()) {
            return " WHERE de.association_id=:associationId";
        }
        if (actor.enterpriseId() != null) {
            return " WHERE (d.enterprise_id=:enterpriseId"
                    + " OR (m.state<>'PENDING_CONFIRMATION'"
                    + " AND m.candidate_enterprise_id=:enterpriseId))"
                    + " AND d.deleted_at IS NULL"
                    + " AND de.status='ACTIVE' AND de.deleted_at IS NULL"
                    + " AND EXISTS (SELECT 1 FROM enterprise read_candidate"
                    + " WHERE read_candidate.id=m.candidate_enterprise_id"
                    + " AND read_candidate.status='ACTIVE' AND read_candidate.deleted_at IS NULL)";
        }
        return " WHERE FALSE";
    }

    private String outcomeMatchScope(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                return actor.enterpriseId() == null ? " WHERE TRUE" : " WHERE FALSE";
            }
            if (actor.enterpriseId() != null) {
                return " WHERE ((de.association_id=:associationId"
                        + " AND d.enterprise_id=:enterpriseId)"
                        + " OR (ce.association_id=:associationId"
                        + " AND m.candidate_enterprise_id=:enterpriseId))";
            }
            return " WHERE (de.association_id=:associationId"
                    + " OR ce.association_id=:associationId)";
        }
        if (actor.isAssociationStaff()) {
            return " WHERE (de.association_id=:associationId OR "
                    + authorizedPartnerMatchRead() + ")";
        }
        if (actor.enterpriseId() != null) {
            return " WHERE ((d.enterprise_id=:enterpriseId"
                    + " OR m.candidate_enterprise_id=:enterpriseId) OR "
                    + authorizedPartnerMatchRead() + ")"
                    + " AND d.deleted_at IS NULL"
                    + " AND de.status='ACTIVE' AND de.deleted_at IS NULL"
                    + " AND ce.status='ACTIVE' AND ce.deleted_at IS NULL";
        }
        return " WHERE FALSE";
    }

    private static String outcomeVisibilityScope() {
        return " AND ((o.visibility='PRIVATE' AND o.archived_by_subject=:subject)"
                + " OR (o.visibility='ENTERPRISES' AND CAST(:enterpriseId AS UUID) IS NOT NULL"
                + " AND (d.enterprise_id=:enterpriseId"
                + " OR m.candidate_enterprise_id=:enterpriseId))"
                + " OR (o.visibility='ASSOCIATION'"
                + " AND o.association_id=:associationId)"
                + " OR o.visibility IN ('PARTNERS','PUBLIC'))";
    }

    private static String authorizedPartnerMatchRead() {
        return "(" + authorizedPartnerOwner("de", "d.enterprise_id")
                + " AND (d.enterprise_id=m.candidate_enterprise_id"
                + " OR ce.association_id=:associationId OR "
                + authorizedPartnerOwner("ce", "m.candidate_enterprise_id") + "))";
    }

    private static String authorizedPartnerOwner(
            String enterpriseAlias, String enterpriseIdExpression) {
        return "(" + enterpriseAlias + ".association_id<>:associationId"
                + " AND " + enterpriseAlias + ".status='ACTIVE'"
                + " AND " + enterpriseAlias + ".deleted_at IS NULL"
                + " AND d.deleted_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM association_relationship ar"
                + " WHERE ar.status='ACTIVE' AND ar.allow_member_data=TRUE"
                + " AND ar.suspended_at IS NULL AND ar.revoked_at IS NULL"
                + " AND (ar.expires_at IS NULL OR ar.expires_at>transaction_timestamp())"
                + " AND ((ar.source_association_id=" + enterpriseAlias + ".association_id"
                + " AND ar.target_association_id=:associationId)"
                + " OR (ar.target_association_id=" + enterpriseAlias + ".association_id"
                + " AND ar.source_association_id=:associationId)))"
                + " AND EXISTS (SELECT 1 FROM association_share_policy sp"
                + " WHERE sp.source_association_id=" + enterpriseAlias + ".association_id"
                + " AND sp.target_association_id=:associationId"
                + " AND sp.resource_type='MATCH' AND sp.status='ACTIVE'"
                + " AND sp.valid_from<=transaction_timestamp()"
                + " AND (sp.expires_at IS NULL OR sp.expires_at>transaction_timestamp()))"
                + " AND EXISTS (SELECT 1 FROM enterprise_share_consent esc"
                + " WHERE esc.enterprise_id=" + enterpriseIdExpression
                + " AND esc.target_association_id=:associationId"
                + " AND esc.resource_type='MATCH' AND esc.resource_id=m.id"
                + " AND esc.status='ACTIVE' AND esc.revoked_at IS NULL"
                + " AND (esc.expires_at IS NULL OR esc.expires_at>transaction_timestamp())))";
    }

    private boolean canReadMatch(UUID matchId, ActorScope actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ecosystem_match m
                      JOIN cooperation_demand d ON d.id=m.demand_id
                      JOIN enterprise de ON de.id=d.enterprise_id
                      JOIN enterprise ce ON ce.id=m.candidate_enterprise_id
                """ + matchScope(actor)
                + " AND m.id=:matchId AND m.deleted_at IS NULL"
                + " AND d.deleted_at IS NULL"
                + " AND de.status='ACTIVE' AND de.deleted_at IS NULL"
                + " AND ce.status='ACTIVE' AND ce.deleted_at IS NULL)",
                scopeParams(actor).addValue("matchId", matchId), Boolean.class));
    }

    private void requireMatchWrite(UUID matchId, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        if (!canReadMatch(matchId, actor)) {
            throw new ForbiddenException(
                    "MATCH_SCOPE_VIOLATION",
                    "workflow record is outside the authenticated data scope");
        }
    }

    private boolean isCandidate(UUID matchId, UUID enterpriseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ecosystem_match
                     WHERE id=:matchId
                       AND candidate_enterprise_id=:enterpriseId)
                """, new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("enterpriseId", enterpriseId), Boolean.class));
    }

    private static void requireAssociationArgument(UUID associationId, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        if (actor.associationId() == null
                || !Objects.equals(actor.associationId(), associationId)) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "workflow association is outside the authenticated data scope");
        }
    }

    private static void requireSelectedEnterprise(UUID enterpriseId, ActorScope actor) {
        if (actor.enterpriseId() == null
                || !actor.enterpriseId().equals(enterpriseId)) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "workflow enterprise is outside the authenticated data scope");
        }
    }

    private void requireRecorder(UUID matchId, UUID enterpriseId, ActorScope actor) {
        if (enterpriseId != null) {
            requireSelectedEnterprise(enterpriseId, actor);
            if (!isParticipant(matchId, enterpriseId)) {
                throw new ForbiddenException(
                        "MATCH_SCOPE_VIOLATION",
                        "negotiation recorder must participate in the match");
            }
            return;
        }
        if (!(actor.isAssociationStaff() || actor.isSystemAdmin())
                || actor.enterpriseId() != null
                || !isDemandAssociation(matchId, actor.associationId())) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "only the demand association can add an association follow-up record");
        }
    }

    private boolean isParticipant(UUID matchId, UUID enterpriseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ecosystem_match m
                      JOIN cooperation_demand d ON d.id=m.demand_id
                     WHERE m.id=:matchId
                       AND (d.enterprise_id=:enterpriseId
                         OR m.candidate_enterprise_id=:enterpriseId))
                """, new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("enterpriseId", enterpriseId), Boolean.class));
    }

    private boolean isDemandAssociation(UUID matchId, UUID associationId) {
        return associationId != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ecosystem_match m
                      JOIN cooperation_demand d ON d.id=m.demand_id
                      JOIN enterprise e ON e.id=d.enterprise_id
                     WHERE m.id=:matchId AND e.association_id=:associationId)
                """, new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId), Boolean.class));
    }

    private static MapSqlParameterSource scopeParams(ActorScope actor) {
        return new MapSqlParameterSource()
                .addValue("associationId", actor.associationId())
                .addValue("enterpriseId", actor.enterpriseId())
                .addValue("subject", actor.subject());
    }

    private MatchInvitationView mapInvitation(ResultSet rs, int rowNum) throws SQLException {
        return new MatchInvitationView(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("sender_enterprise_id", UUID.class),
                rs.getObject("recipient_enterprise_id", UUID.class),
                rs.getString("invitation_type"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getString("response_comment"),
                rs.getString("sent_by_subject"),
                rs.getString("responded_by_subject"),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("responded_at")),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private NegotiationView mapNegotiation(ResultSet rs, int rowNum) throws SQLException {
        return new NegotiationView(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("stage"),
                rs.getString("summary"),
                rs.getString("next_action"),
                instant(rs.getTimestamp("next_action_at")),
                rs.getString("recorded_by_subject"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("version"));
    }

    private MatchFeedbackView mapFeedback(ResultSet rs, int rowNum) throws SQLException {
        int rating = rs.getInt("rating");
        return new MatchFeedbackView(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.wasNull() ? null : rating,
                rs.getString("outcome"),
                rs.getString("close_reason"),
                rs.getString("comment"),
                rs.getString("submitted_by_subject"),
                rs.getTimestamp("submitted_at").toInstant(),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private OutcomeArchiveView mapOutcome(ResultSet rs, int rowNum) throws SQLException {
        return new OutcomeArchiveView(
                rs.getObject("id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getBigDecimal("contract_amount"),
                rs.getString("result_type"),
                rs.getString("visibility"),
                rs.getString("archived_by_subject"),
                rs.getTimestamp("archived_at").toInstant(),
                rs.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
