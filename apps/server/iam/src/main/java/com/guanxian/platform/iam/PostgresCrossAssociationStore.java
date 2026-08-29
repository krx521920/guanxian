package com.guanxian.platform.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresCrossAssociationStore implements CrossAssociationStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    PostgresCrossAssociationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<CrossAssociationDtos.AccessRequestView> accessRequests() {
        return jdbc.query("SELECT * FROM association_access_request ORDER BY requested_at DESC",
                (rs, row) -> accessRequest(rs));
    }

    @Override
    public Optional<CrossAssociationDtos.AccessRequestView> accessRequest(UUID id) {
        return one("SELECT * FROM association_access_request WHERE id=:id", params("id", id), this::accessRequest);
    }

    @Override
    public CrossAssociationDtos.AccessRequestView insertAccessRequest(
            UUID source, UUID target, String reason, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO association_access_request
                      (id, applicant_association_id, target_association_id, reason, status,
                       requested_by_subject, requested_at)
                    VALUES (:id, :source, :target, :reason, 'PENDING', :subject, :now)
                    """, params("id", id).addValue("source", source).addValue("target", target)
                    .addValue("reason", reason).addValue("subject", actor.subject()).addValue("now", now));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("a pending access request already exists between these associations");
        }
        return accessRequest(id).orElseThrow();
    }

    @Override
    public CrossAssociationDtos.AccessRequestView reviewAccessRequest(
            UUID id, String status, String comment, ActorScope actor, Instant now) {
        int changed = jdbc.update("""
                UPDATE association_access_request
                SET status=:status, reviewed_by_subject=:subject, review_comment=:comment, reviewed_at=:now
                WHERE id=:id AND status='PENDING'
                """, params("id", id).addValue("status", status).addValue("subject", actor.subject())
                .addValue("comment", comment).addValue("now", now));
        if (changed != 1) throw new ConflictException("access request is no longer pending");
        return accessRequest(id).orElseThrow();
    }

    @Override
    public List<CrossAssociationDtos.RelationshipView> relationships() {
        return jdbc.query("SELECT * FROM association_relationship ORDER BY updated_at DESC",
                (rs, row) -> relationship(rs));
    }

    @Override
    public Optional<CrossAssociationDtos.RelationshipView> relationship(UUID source, UUID target) {
        return one("""
                SELECT * FROM association_relationship
                WHERE (source_association_id=:source AND target_association_id=:target)
                   OR (source_association_id=:target AND target_association_id=:source)
                """, params("source", source).addValue("target", target), this::relationship);
    }

    @Override
    public CrossAssociationDtos.RelationshipView establishRelationship(
            UUID source, UUID target, boolean allowMemberData, Instant expiresAt, ActorScope actor, Instant now) {
        var old = relationship(source, target).orElse(null);
        if (old == null) {
            try {
                jdbc.update("""
                        INSERT INTO association_relationship
                          (source_association_id, target_association_id, status, allow_member_data,
                           expires_at, version, created_at, updated_at)
                        VALUES (:source, :target, 'ACTIVE', :allow, :expiresAt, 0, :now, :now)
                        """, params("source", source).addValue("target", target).addValue("allow", allowMemberData)
                        .addValue("expiresAt", expiresAt).addValue("now", now));
            } catch (DataIntegrityViolationException exception) {
                throw new ConflictException("association relationship was established concurrently");
            }
        } else {
            jdbc.update("""
                    UPDATE association_relationship
                    SET status='ACTIVE', allow_member_data=:allow, expires_at=:expiresAt,
                        suspended_at=NULL, suspended_by_association_id=NULL, suspended_by_subject=NULL,
                        revoked_at=NULL, revoked_by_subject=NULL, revoke_reason=NULL,
                        version=version+1, updated_at=:now
                    WHERE source_association_id=:source AND target_association_id=:target
                    """, params("source", old.sourceAssociationId()).addValue("target", old.targetAssociationId())
                    .addValue("allow", allowMemberData).addValue("expiresAt", expiresAt).addValue("now", now));
            source = old.sourceAssociationId();
            target = old.targetAssociationId();
        }
        return relationship(source, target).orElseThrow();
    }

    @Override
    public CrossAssociationDtos.RelationshipView updateRelationship(
            UUID source, UUID target, long expectedVersion, String status, Instant expiresAt,
            Instant suspendedAt, UUID suspendedByAssociationId, String suspendedBySubject,
            Instant revokedAt, String reason, ActorScope actor, Instant now) {
        var old = relationship(source, target).orElseThrow();
        int changed = jdbc.update("""
                UPDATE association_relationship
                SET status=:status, expires_at=:expiresAt, suspended_at=:suspendedAt,
                    suspended_by_association_id=:suspendedByAssociationId,
                    suspended_by_subject=:suspendedBySubject, revoked_at=:revokedAt,
                    revoked_by_subject=CASE WHEN :status='REVOKED' THEN :subject ELSE revoked_by_subject END,
                    revoke_reason=CASE WHEN :status='REVOKED' THEN :reason ELSE revoke_reason END,
                    version=version+1, updated_at=:now
                WHERE source_association_id=:source AND target_association_id=:target AND version=:version
                """, params("source", old.sourceAssociationId()).addValue("target", old.targetAssociationId())
                .addValue("version", expectedVersion).addValue("status", status).addValue("expiresAt", expiresAt)
                .addValue("suspendedAt", suspendedAt)
                .addValue("suspendedByAssociationId", suspendedByAssociationId)
                .addValue("suspendedBySubject", suspendedBySubject).addValue("revokedAt", revokedAt)
                .addValue("subject", actor.subject()).addValue("reason", reason).addValue("now", now));
        requireUpdated(changed);
        return relationship(old.sourceAssociationId(), old.targetAssociationId()).orElseThrow();
    }

    @Override
    public List<CrossAssociationDtos.SharePolicyView> sharePolicies() {
        return jdbc.query("SELECT * FROM association_share_policy ORDER BY updated_at DESC",
                (rs, row) -> sharePolicy(rs));
    }

    @Override
    public Optional<CrossAssociationDtos.SharePolicyView> sharePolicy(UUID id) {
        return one("SELECT * FROM association_share_policy WHERE id=:id", params("id", id), this::sharePolicy);
    }

    @Override
    public CrossAssociationDtos.SharePolicyView insertSharePolicy(
            UUID source, CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO association_share_policy
                      (id, source_association_id, target_association_id, resource_type, visible_fields,
                       status, valid_from, expires_at, created_by_subject, version, created_at, updated_at)
                    VALUES (:id, :source, :target, :resourceType, CAST(:visibleFields AS jsonb),
                            :status, :validFrom, :expiresAt, :subject, 0, :now, :now)
                    """, policyParams(id, source, request, actor, now));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("a share policy already exists for this resource type");
        }
        return sharePolicy(id).orElseThrow();
    }

    @Override
    public CrossAssociationDtos.SharePolicyView updateSharePolicy(
            UUID id, long expectedVersion, CrossAssociationDtos.SharePolicyUpsert request,
            ActorScope actor, Instant now) {
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE association_share_policy
                    SET target_association_id=:target, resource_type=:resourceType,
                        visible_fields=CAST(:visibleFields AS jsonb), status=:status,
                        valid_from=:validFrom, expires_at=:expiresAt, version=version+1, updated_at=:now
                    WHERE id=:id AND version=:version
                    """, policyParams(id, null, request, actor, now).addValue("version", expectedVersion));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("a share policy already exists for this resource type");
        }
        requireUpdated(changed);
        return sharePolicy(id).orElseThrow();
    }

    @Override
    public List<CrossAssociationDtos.ConsentView> consents() {
        return jdbc.query("SELECT * FROM enterprise_share_consent ORDER BY created_at DESC",
                (rs, row) -> consent(rs));
    }

    @Override
    public Optional<CrossAssociationDtos.ConsentView> consent(UUID id) {
        return one("SELECT * FROM enterprise_share_consent WHERE id=:id", params("id", id), this::consent);
    }

    @Override
    public CrossAssociationDtos.ConsentView insertConsent(
            UUID enterpriseId, CrossAssociationDtos.ConsentCreate request, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO enterprise_share_consent
                  (id, enterprise_id, target_association_id, resource_type, resource_id, status,
                   granted_by_subject, expires_at, created_at)
                VALUES (:id, :enterpriseId, :target, :resourceType, :resourceId, 'ACTIVE',
                        :subject, :expiresAt, :now)
                """, params("id", id).addValue("enterpriseId", enterpriseId)
                .addValue("target", request.targetAssociationId())
                .addValue("resourceType", request.resourceType().trim().toUpperCase())
                .addValue("resourceId", request.resourceId()).addValue("subject", actor.subject())
                .addValue("expiresAt", request.expiresAt()).addValue("now", now));
        return consent(id).orElseThrow();
    }

    @Override
    public CrossAssociationDtos.ConsentView revokeConsent(UUID id, ActorScope actor, Instant now) {
        int changed = jdbc.update("""
                UPDATE enterprise_share_consent SET status='REVOKED', revoked_at=:now
                WHERE id=:id AND status='ACTIVE'
                """, params("id", id).addValue("now", now));
        if (changed != 1) throw new ConflictException("share consent is no longer active");
        return consent(id).orElseThrow();
    }

    @Override
    public List<CrossAssociationDtos.RecommendationView> recommendations() {
        return jdbc.query("SELECT * FROM cross_association_recommendation ORDER BY created_at DESC",
                (rs, row) -> recommendation(rs));
    }

    @Override
    public Optional<CrossAssociationDtos.RecommendationView> recommendation(UUID id) {
        return one("SELECT * FROM cross_association_recommendation WHERE id=:id",
                params("id", id), this::recommendation);
    }

    @Override
    public CrossAssociationDtos.RecommendationView insertRecommendation(
            UUID source, CrossAssociationDtos.RecommendationCreate request, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cross_association_recommendation
                  (id, source_association_id, target_association_id, demand_id, match_id,
                   status, summary, created_by_subject, created_at, version)
                VALUES (:id, :source, :target, :demandId, :matchId,
                        'PENDING_REVIEW', :summary, :subject, :now, 0)
                """, params("id", id).addValue("source", source).addValue("target", request.targetAssociationId())
                .addValue("demandId", request.demandId()).addValue("matchId", request.matchId())
                .addValue("summary", request.summary().trim()).addValue("subject", actor.subject())
                .addValue("now", now));
        return recommendation(id).orElseThrow();
    }

    @Override
    public CrossAssociationDtos.RecommendationView reviewRecommendation(
            UUID id, long expectedVersion, String status, String comment, ActorScope actor, Instant now) {
        int changed = jdbc.update("""
                UPDATE cross_association_recommendation
                SET status=:status, reviewed_by_subject=:subject, review_comment=:comment,
                    reviewed_at=:now, version=version+1
                WHERE id=:id AND version=:version AND status='PENDING_REVIEW'
                """, params("id", id).addValue("version", expectedVersion).addValue("status", status)
                .addValue("subject", actor.subject()).addValue("comment", comment).addValue("now", now));
        requireUpdated(changed);
        return recommendation(id).orElseThrow();
    }

    @Override
    public Optional<UUID> enterpriseAssociation(UUID enterpriseId) {
        List<UUID> values = jdbc.queryForList("SELECT association_id FROM enterprise WHERE id=:id",
                params("id", enterpriseId), UUID.class);
        return values.stream().findFirst();
    }

    @Override
    public boolean associationExists(UUID associationId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM association WHERE id=:id",
                params("id", associationId), Integer.class);
        return count != null && count == 1;
    }

    @Override
    public boolean resourceOwnedByEnterprise(String resourceType, UUID resourceId, UUID enterpriseId) {
        MapSqlParameterSource values = params("resourceId", resourceId).addValue("enterpriseId", enterpriseId);
        String sql = switch (resourceType) {
            case "PRODUCT", "SERVICE" -> """
                    SELECT COUNT(*) FROM product_service
                    WHERE id=:resourceId AND enterprise_id=:enterpriseId
                      AND kind=:resourceType AND deleted_at IS NULL
                    """;
            case "DEMAND" -> """
                    SELECT COUNT(*) FROM cooperation_demand
                    WHERE id=:resourceId AND enterprise_id=:enterpriseId AND deleted_at IS NULL
                    """;
            case "MATCH" -> """
                    SELECT COUNT(*)
                    FROM ecosystem_match m
                    JOIN cooperation_demand d ON d.id=m.demand_id
                    WHERE m.id=:resourceId AND m.deleted_at IS NULL AND d.deleted_at IS NULL
                      AND (d.enterprise_id=:enterpriseId OR m.candidate_enterprise_id=:enterpriseId)
                    """;
            default -> null;
        };
        if (sql == null) return false;
        values.addValue("resourceType", resourceType);
        Integer count = jdbc.queryForObject(sql, values, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public Optional<CrossAssociationStore.DemandOwnership> demandOwnership(UUID demandId) {
        return one("""
                SELECT d.id AS demand_id, d.enterprise_id, e.association_id
                FROM cooperation_demand d
                JOIN enterprise e ON e.id=d.enterprise_id
                WHERE d.id=:id AND d.deleted_at IS NULL
                """, params("id", demandId), rs -> new CrossAssociationStore.DemandOwnership(
                rs.getObject("demand_id", UUID.class), rs.getObject("enterprise_id", UUID.class),
                rs.getObject("association_id", UUID.class)));
    }

    @Override
    public Optional<CrossAssociationStore.MatchOwnership> matchOwnership(UUID matchId) {
        return one("""
                SELECT m.id AS match_id, m.demand_id,
                       d.enterprise_id AS demand_enterprise_id,
                       de.association_id AS demand_association_id,
                       m.candidate_enterprise_id,
                       ce.association_id AS candidate_association_id
                FROM ecosystem_match m
                JOIN cooperation_demand d ON d.id=m.demand_id
                JOIN enterprise de ON de.id=d.enterprise_id
                JOIN enterprise ce ON ce.id=m.candidate_enterprise_id
                WHERE m.id=:id AND m.deleted_at IS NULL AND d.deleted_at IS NULL
                """, params("id", matchId), rs -> new CrossAssociationStore.MatchOwnership(
                rs.getObject("match_id", UUID.class), rs.getObject("demand_id", UUID.class),
                rs.getObject("demand_enterprise_id", UUID.class),
                rs.getObject("demand_association_id", UUID.class),
                rs.getObject("candidate_enterprise_id", UUID.class),
                rs.getObject("candidate_association_id", UUID.class)));
    }

    @Override
    public void audit(ActorScope actor, UUID associationId, UUID enterpriseId,
                      String action, String resourceType, Object resourceId, Object details) {
        jdbc.update("""
                INSERT INTO audit_log
                  (actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                   action, resource_type, resource_id, resource_version, outcome, details, request_id)
                VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                        :actorSubject, COALESCE(:actorUsername, :actorSubject), :associationId, :enterpriseId,
                        :action, :resourceType, :resourceId, NULL, 'SUCCESS',
                        CAST(:details AS jsonb), COALESCE(:requestId, 'internal'))
                """, params("actorUserId", actor.userId()).addValue("actorSubject", actor.subject())
                .addValue("actorUsername", actor.username()).addValue("associationId", associationId)
                .addValue("enterpriseId", enterpriseId).addValue("action", action)
                .addValue("resourceType", resourceType).addValue("resourceId", String.valueOf(resourceId))
                .addValue("details", writeJson(details)).addValue("requestId", MDC.get("requestId")));
    }

    private MapSqlParameterSource policyParams(
            UUID id, UUID source, CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor, Instant now) {
        return params("id", id).addValue("source", source).addValue("target", request.targetAssociationId())
                .addValue("resourceType", request.resourceType()).addValue("visibleFields", writeJson(request.visibleFields()))
                .addValue("status", request.status()).addValue("validFrom", request.validFrom())
                .addValue("expiresAt", request.expiresAt()).addValue("subject", actor.subject()).addValue("now", now);
    }

    private CrossAssociationDtos.AccessRequestView accessRequest(ResultSet rs) throws SQLException {
        return new CrossAssociationDtos.AccessRequestView(rs.getObject("id", UUID.class),
                rs.getObject("applicant_association_id", UUID.class), rs.getObject("target_association_id", UUID.class),
                rs.getString("reason"), rs.getString("status"), rs.getString("requested_by_subject"),
                rs.getString("reviewed_by_subject"), rs.getString("review_comment"),
                instant(rs, "requested_at"), instant(rs, "reviewed_at"));
    }

    private CrossAssociationDtos.RelationshipView relationship(ResultSet rs) throws SQLException {
        return new CrossAssociationDtos.RelationshipView(rs.getObject("source_association_id", UUID.class),
                rs.getObject("target_association_id", UUID.class), rs.getString("status"),
                rs.getBoolean("allow_member_data"), instant(rs, "expires_at"), instant(rs, "suspended_at"),
                rs.getObject("suspended_by_association_id", UUID.class), rs.getString("suspended_by_subject"),
                instant(rs, "revoked_at"), rs.getString("revoked_by_subject"), rs.getString("revoke_reason"),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private CrossAssociationDtos.SharePolicyView sharePolicy(ResultSet rs) throws SQLException {
        return new CrossAssociationDtos.SharePolicyView(rs.getObject("id", UUID.class),
                rs.getObject("source_association_id", UUID.class), rs.getObject("target_association_id", UUID.class),
                rs.getString("resource_type"), readStringList(rs.getString("visible_fields")), rs.getString("status"),
                instant(rs, "valid_from"), instant(rs, "expires_at"), rs.getString("created_by_subject"),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private CrossAssociationDtos.ConsentView consent(ResultSet rs) throws SQLException {
        return new CrossAssociationDtos.ConsentView(rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class), rs.getObject("target_association_id", UUID.class),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class), rs.getString("status"),
                rs.getString("granted_by_subject"), instant(rs, "expires_at"), instant(rs, "revoked_at"),
                instant(rs, "created_at"));
    }

    private CrossAssociationDtos.RecommendationView recommendation(ResultSet rs) throws SQLException {
        return new CrossAssociationDtos.RecommendationView(rs.getObject("id", UUID.class),
                rs.getObject("source_association_id", UUID.class), rs.getObject("target_association_id", UUID.class),
                rs.getObject("demand_id", UUID.class), rs.getObject("match_id", UUID.class), rs.getString("status"),
                rs.getString("summary"), rs.getString("created_by_subject"), rs.getString("reviewed_by_subject"),
                rs.getString("review_comment"), instant(rs, "created_at"), instant(rs, "reviewed_at"),
                rs.getLong("version"));
    }

    private List<String> readStringList(String value) {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid visible_fields JSON in database", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cross-association audit could not be serialized", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, java.time.OffsetDateTime.class) == null
                ? null : rs.getObject(column, java.time.OffsetDateTime.class).toInstant();
    }

    private static MapSqlParameterSource params(String name, Object value) {
        return new MapSqlParameterSource(name, value);
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, SqlMapper<T> mapper) {
        List<T> values = jdbc.query(sql, params, (rs, row) -> mapper.map(rs));
        return values.stream().findFirst();
    }

    private static void requireUpdated(int changed) {
        if (changed != 1) {
            throw new PreconditionFailedException("resource version does not match If-Match");
        }
    }

    @FunctionalInterface
    private interface SqlMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }
}
