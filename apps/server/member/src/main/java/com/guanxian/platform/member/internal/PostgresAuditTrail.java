package com.guanxian.platform.member.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "guanxian.member.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresAuditTrail implements AuditTrail {
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {
    };
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PostgresAuditTrail(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(ActorScope actor, String action, String resourceType, String resourceId,
                       UUID associationId, UUID enterpriseId, Map<String, Object> details) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log (
                        actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                        action, resource_type, resource_id, resource_version, outcome, details, request_id)
                    VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                            :actorSubject, COALESCE(:actorUsername, :actorSubject), :associationId, :enterpriseId,
                            :action, :resourceType, :resourceId, :resourceVersion, 'SUCCESS',
                            CAST(:details AS jsonb), COALESCE(:requestId, 'internal'))
                    """, new MapSqlParameterSource()
                    .addValue("actorUserId", actor.userId())
                    .addValue("actorSubject", actor.subject())
                    .addValue("actorUsername", actor.username())
                    .addValue("associationId", associationId)
                    .addValue("enterpriseId", enterpriseId)
                    .addValue("action", action)
                    .addValue("resourceType", resourceType)
                    .addValue("resourceId", resourceId)
                    .addValue("resourceVersion", details.get("newVersion") instanceof Number number
                            && number.longValue() >= 0 ? number.longValue() : null)
                    .addValue("details", objectMapper.writeValueAsString(details))
                    .addValue("requestId", MDC.get("requestId")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("audit details could not be serialized", exception);
        }
    }

    @Override
    public void recordReview(
            ActorScope actor, UUID associationId, UUID enterpriseId, String previousStatus, String decision, String comment) {
        jdbc.update("""
                INSERT INTO member_review (
                    enterprise_id, reviewer_user_id, reviewer_subject, previous_status, decision, comment)
                VALUES (:enterpriseId, :reviewerUserId, :reviewerSubject, :previousStatus, :decision, :comment)
                """, new MapSqlParameterSource()
                .addValue("enterpriseId", enterpriseId)
                .addValue("reviewerUserId", actor.userId())
                .addValue("reviewerSubject", actor.subject())
                .addValue("previousStatus", previousStatus)
                .addValue("decision", decision)
                .addValue("comment", comment));
        record(actor, "MEMBER_REVIEW", "ENTERPRISE", enterpriseId.toString(), associationId, enterpriseId,
                Map.of("previousStatus", previousStatus, "decision", decision,
                        "comment", comment == null ? "" : comment));
    }

    @Override
    public List<AuditRecord> findVisible(ActorScope actor, UUID enterpriseId, int limit) {
        String scope = actor.isSystemAdmin() ? "" : " AND association_id = :associationId";
        String enterprise = enterpriseId == null ? "" : " AND enterprise_id = :enterpriseId";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("associationId", actor.associationId())
                .addValue("enterpriseId", enterpriseId)
                .addValue("limit", limit);
        return jdbc.query("""
                SELECT id, actor_subject, actor_username, association_id, enterprise_id,
                       action, resource_type, resource_id, resource_version, outcome,
                       details, request_id, occurred_at
                FROM audit_log
                WHERE 1 = 1
                """ + scope + enterprise + " ORDER BY occurred_at DESC, id DESC LIMIT :limit",
                parameters, (rs, row) -> new AuditRecord(
                        rs.getLong("id"), rs.getString("actor_subject"), rs.getString("actor_username"),
                        rs.getObject("association_id", UUID.class), rs.getObject("enterprise_id", UUID.class),
                        rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                        rs.getObject("resource_version", Long.class), rs.getString("outcome"),
                        readDetails(rs.getString("details")), rs.getString("request_id"),
                        rs.getTimestamp("occurred_at").toInstant()));
    }

    private Map<String, Object> readDetails(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, DETAILS);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored audit details are invalid JSON", exception);
        }
    }
}
