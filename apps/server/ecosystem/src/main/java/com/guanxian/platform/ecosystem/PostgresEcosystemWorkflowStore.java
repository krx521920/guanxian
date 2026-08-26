package com.guanxian.platform.ecosystem;

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
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId)
                .addValue("senderEnterpriseId", senderEnterpriseId)
                .addValue("recipientEnterpriseId", request.recipientEnterpriseId())
                .addValue("invitationType", request.invitationType())
                .addValue("message", clean(request.message()))
                .addValue("expiresAt", request.expiresAt())
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
    public Optional<MatchInvitationView> respondInvitation(
            UUID invitationId,
            long expectedVersion,
            boolean accepted,
            String comment,
            ActorScope actor) {
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
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId)
                .addValue("enterpriseId", enterpriseId)
                .addValue("stage", request.stage().trim())
                .addValue("summary", request.summary().trim())
                .addValue("nextAction", clean(request.nextAction()))
                .addValue("nextActionAt", request.nextActionAt())
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
                       next_action_at, recorded_by_subject, created_at
                  FROM negotiation_record
                 WHERE id=:id
                """, new MapSqlParameterSource("id", id), this::mapNegotiation).getFirst();
    }

    @Override
    public List<NegotiationView> negotiations(UUID matchId, ActorScope actor) {
        return jdbc.query("""
                SELECT n.id, n.match_id, n.enterprise_id, n.stage, n.summary, n.next_action,
                       n.next_action_at, n.recorded_by_subject, n.created_at
                  FROM negotiation_record n
                  JOIN ecosystem_match m ON m.id=n.match_id
                  JOIN cooperation_demand d ON d.id=m.demand_id
                  JOIN enterprise de ON de.id=d.enterprise_id
                """ + matchScope(actor)
                        + " AND n.match_id=:matchId ORDER BY n.created_at DESC, n.id",
                scopeParams(actor).addValue("matchId", matchId), this::mapNegotiation);
    }

    @Override
    public MatchFeedbackView upsertFeedback(
            UUID matchId, UUID enterpriseId, MatchFeedbackRequest request, ActorScope actor) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("enterpriseId", enterpriseId)
                .addValue("rating", request.rating())
                .addValue("outcome", request.outcome().trim())
                .addValue("closeReason", clean(request.closeReason()))
                .addValue("comment", clean(request.comment()))
                .addValue("subject", actor.subject());
        UUID id = jdbc.queryForObject("""
                INSERT INTO match_feedback (
                    match_id, enterprise_id, rating, outcome, close_reason,
                    comment, submitted_by_subject)
                VALUES (
                    :matchId, :enterpriseId, :rating, :outcome, :closeReason,
                    :comment, :subject)
                ON CONFLICT (match_id, enterprise_id)
                DO UPDATE SET
                    rating=excluded.rating,
                    outcome=excluded.outcome,
                    close_reason=excluded.close_reason,
                    comment=excluded.comment,
                    submitted_by_subject=excluded.submitted_by_subject,
                    submitted_at=now()
                RETURNING id
                """, params, UUID.class);
        return jdbc.query("""
                SELECT id, match_id, enterprise_id, rating, outcome, close_reason,
                       comment, submitted_by_subject, submitted_at
                  FROM match_feedback
                 WHERE id=:id
                """, new MapSqlParameterSource("id", id), this::mapFeedback).getFirst();
    }

    @Override
    public OutcomeArchiveView archive(
            UUID matchId, UUID associationId, OutcomeArchiveRequest request, ActorScope actor) {
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
                """ + matchScope(actor)
                        + " AND o.match_id=:matchId AND o.deleted_at IS NULL"
                        + " ORDER BY o.archived_at DESC, o.id",
                scopeParams(actor).addValue("matchId", matchId), this::mapOutcome);
    }

    private String scope(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return " WHERE TRUE";
        }
        if (actor.isAssociationStaff()) {
            return " WHERE de.association_id=:associationId";
        }
        if (actor.enterpriseId() != null) {
            return " WHERE (d.enterprise_id=:enterpriseId"
                    + " OR m.candidate_enterprise_id=:enterpriseId"
                    + " OR i.recipient_enterprise_id=:enterpriseId)";
        }
        return " WHERE FALSE";
    }

    private String matchScope(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return " WHERE TRUE";
        }
        if (actor.isAssociationStaff()) {
            return " WHERE de.association_id=:associationId";
        }
        if (actor.enterpriseId() != null) {
            return " WHERE (d.enterprise_id=:enterpriseId OR m.candidate_enterprise_id=:enterpriseId)";
        }
        return " WHERE FALSE";
    }

    private static MapSqlParameterSource scopeParams(ActorScope actor) {
        return new MapSqlParameterSource()
                .addValue("associationId", actor.associationId())
                .addValue("enterpriseId", actor.enterpriseId());
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
                rs.getTimestamp("created_at").toInstant());
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
                rs.getTimestamp("submitted_at").toInstant());
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

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
