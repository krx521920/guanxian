package com.guanxian.platform.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresCollaborationStore implements CollaborationStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String SELECT = """
            SELECT c.id, c.association_id, c.enterprise_id, c.match_id, c.title,
                   c.participants::text AS participants,
                   coalesce(c.owner_subject, u.display_name) AS owner,
                   c.status, c.priority, c.next_action, c.due_at, c.progress,
                   c.version, c.disabled_at, c.deleted_at, c.updated_at
              FROM collaboration_task c
              LEFT JOIN user_account u ON u.id = c.owner_user_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<CollaborationView> mapper = this::map;

    PostgresCollaborationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CollaborationView> list(
            ActorScope actor, String query, boolean includeDeleted, long offset, int limit) {
        MapSqlParameterSource values = params(actor, query).addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(SELECT + where(actor, query, includeDeleted)
                + " ORDER BY c.updated_at DESC, c.id LIMIT :limit OFFSET :offset", values, mapper);
    }

    @Override
    public long count(ActorScope actor, String query, boolean includeDeleted) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM collaboration_task c"
                + where(actor, query, includeDeleted), params(actor, query), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<CollaborationView> find(UUID id, ActorScope actor, boolean includeDeleted) {
        List<CollaborationView> values = jdbc.query(
                SELECT + where(actor, null, includeDeleted) + " AND c.id=:id",
                params(actor, null).addValue("id", id), mapper);
        return values.stream().findFirst();
    }

    @Override
    public boolean canLinkMatch(UUID matchId, UUID associationId, UUID enterpriseId) {
        if (matchId == null) {
            return true;
        }
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM ecosystem_match m
                      JOIN cooperation_demand d ON d.id=m.demand_id
                      JOIN enterprise de ON de.id=d.enterprise_id
                      JOIN enterprise ce ON ce.id=m.candidate_enterprise_id
                     WHERE m.id=:matchId
                       AND m.deleted_at IS NULL
                       AND d.deleted_at IS NULL
                       AND de.status='ACTIVE' AND de.deleted_at IS NULL
                       AND ce.status='ACTIVE' AND ce.deleted_at IS NULL
                       AND de.association_id=:associationId
                       AND (CAST(:enterpriseId AS UUID) IS NULL
                            OR d.enterprise_id=:enterpriseId
                            OR m.candidate_enterprise_id=:enterpriseId)
                )
                """, new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("associationId", associationId)
                .addValue("enterpriseId", enterpriseId), Boolean.class);
        return Boolean.TRUE.equals(allowed);
    }

    @Override
    public CollaborationView create(
            UUID associationId, UUID enterpriseId, CollaborationUpsertRequest request, ActorScope actor) {
        UUID id = UUID.randomUUID();
        MapSqlParameterSource values = valueParams(request, actor)
                .addValue("id", id).addValue("associationId", associationId).addValue("enterpriseId", enterpriseId);
        jdbc.update("""
                INSERT INTO collaboration_task (
                    id, association_id, enterprise_id, match_id, owner_subject, title,
                    participants, priority, next_action, progress, status, due_at)
                VALUES (
                    :id, :associationId, :enterpriseId, :matchId, :owner, :title,
                    CAST(:participants AS jsonb), :priority, :nextAction, :progress, 'DRAFT', :dueAt)
                """, values);
        return find(id, actor, false).orElseThrow();
    }

    @Override
    public Optional<CollaborationView> update(
            UUID id, long expectedVersion, CollaborationUpsertRequest request, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE collaboration_task
                   SET title=:title, participants=CAST(:participants AS jsonb), owner_subject=:owner,
                       match_id=:matchId,
                       priority=:priority, next_action=:nextAction, due_at=:dueAt, progress=:progress,
                       version=version+1, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """, valueParams(request, actor).addValue("id", id).addValue("expectedVersion", expectedVersion));
        return updated == 0 ? Optional.empty() : find(id, actor, false);
    }

    @Override
    public Optional<CollaborationView> transition(
            UUID id, long expectedVersion, String stage, boolean disabled, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE collaboration_task
                   SET status=:stage,
                       disabled_at=CASE WHEN :disabled THEN now() ELSE NULL END,
                       completed_at=CASE WHEN :stage='COMPLETED' THEN now() ELSE NULL END,
                       progress=CASE WHEN :stage='COMPLETED' THEN 100 ELSE progress END,
                       version=version+1, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("id", id).addValue("expectedVersion", expectedVersion)
                .addValue("stage", stage).addValue("disabled", disabled));
        return updated == 0 ? Optional.empty() : find(id, actor, false);
    }

    @Override
    public Optional<CollaborationView> softDelete(UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE collaboration_task
                   SET deleted_at=now(), version=version+1, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """, new MapSqlParameterSource().addValue("id", id).addValue("expectedVersion", expectedVersion));
        return updated == 0 ? Optional.empty() : find(id, actor, true);
    }

    @Override
    public Optional<CollaborationView> restore(UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE collaboration_task
                   SET deleted_at=NULL, disabled_at=NULL, completed_at=NULL,
                       status='DRAFT', version=version+1, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NOT NULL
                """, new MapSqlParameterSource().addValue("id", id).addValue("expectedVersion", expectedVersion));
        return updated == 0 ? Optional.empty() : find(id, actor, false);
    }

    @Override
    public CollaborationActivityView appendActivity(
            UUID collaborationId, String type, String detail, ActorScope actor) {
        MapSqlParameterSource values = new MapSqlParameterSource()
                .addValue("collaborationId", collaborationId).addValue("type", type)
                .addValue("detail", detail).addValue("subject", actor.subject());
        CollaborationActivityView activity = jdbc.queryForObject("""
                INSERT INTO collaboration_activity (
                    collaboration_id, activity_type, detail, actor_subject)
                VALUES (:collaborationId, :type, :detail, :subject)
                RETURNING id, activity_type, detail, actor_subject, occurred_at
                """, values, this::mapActivity);
        Map<String, Object> scope = jdbc.queryForMap("""
                SELECT association_id, enterprise_id, version FROM collaboration_task
                 WHERE id=:collaborationId
                """, values);
        values.addValue("associationId", scope.get("association_id"))
                .addValue("enterpriseId", scope.get("enterprise_id"))
                .addValue("version", scope.get("version"))
                .addValue("snapshot", json(Map.of("type", type, "detail", detail)));
        insertHistory(values, "ADD_ACTIVITY");
        insertAudit(values, actor, "ADD_ACTIVITY");
        return activity;
    }

    @Override
    public List<CollaborationActivityView> activities(UUID collaborationId, int limit) {
        return jdbc.query("""
                SELECT id, activity_type, detail, actor_subject, occurred_at
                  FROM collaboration_activity WHERE collaboration_id=:collaborationId
                 ORDER BY occurred_at DESC, id DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("collaborationId", collaborationId)
                .addValue("limit", limit), this::mapActivity);
    }

    @Override
    public List<CollaborationHistoryView> history(UUID collaborationId, int limit) {
        return jdbc.query("""
                SELECT id, resource_version, action, actor_subject,
                       snapshot::text AS snapshot, occurred_at
                  FROM business_entity_history
                 WHERE resource_type='COLLABORATION_TASK' AND resource_id=:collaborationId
                 ORDER BY occurred_at DESC, id DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("collaborationId", collaborationId)
                .addValue("limit", limit), this::mapHistory);
    }

    @Override
    public void recordChange(ActorScope actor, String action, CollaborationView value, String detail) {
        MapSqlParameterSource values = new MapSqlParameterSource()
                .addValue("collaborationId", value.id()).addValue("associationId", value.associationId())
                .addValue("enterpriseId", value.enterpriseId()).addValue("version", value.version())
                .addValue("subject", actor.subject()).addValue("type", action)
                .addValue("detail", detail == null || detail.isBlank()
                        ? action + ": " + value.title() : detail.trim())
                .addValue("snapshot", json(value));
        insertHistory(values, action);
        insertAudit(values, actor, action);
        jdbc.update("""
                INSERT INTO collaboration_activity (
                    collaboration_id, activity_type, detail, actor_subject)
                VALUES (:collaborationId, :type, :detail, :subject)
                """, values);
    }

    private void insertHistory(MapSqlParameterSource values, String action) {
        values.addValue("action", action);
        jdbc.update("""
                INSERT INTO business_entity_history (
                    association_id, enterprise_id, resource_type, resource_id,
                    resource_version, action, actor_subject, snapshot)
                VALUES (
                    :associationId, :enterpriseId, 'COLLABORATION_TASK',
                    :collaborationId, :version, :action, :subject, CAST(:snapshot AS jsonb))
                """, values);
    }

    private void insertAudit(MapSqlParameterSource values, ActorScope actor, String action) {
        values.addValue("actorUserId", actor.userId()).addValue("actorUsername", actor.username())
                .addValue("requestId", MDC.get("requestId")).addValue("action", action);
        jdbc.update("""
                INSERT INTO audit_log (
                    actor_user_id, actor_subject, actor_username, association_id,
                    enterprise_id, action, resource_type, resource_id, resource_version,
                    outcome, details, request_id)
                VALUES (
                    (SELECT id FROM user_account WHERE id = :actorUserId),
                    :subject, COALESCE(:actorUsername, :subject), :associationId,
                    :enterpriseId, :action, 'COLLABORATION_TASK',
                    CAST(:collaborationId AS varchar), :version, 'SUCCESS',
                    CAST(:snapshot AS jsonb), COALESCE(:requestId, 'internal'))
                """, values);
    }

    private String where(ActorScope actor, String query, boolean includeDeleted) {
        StringBuilder sql = new StringBuilder(" WHERE ");
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                sql.append("TRUE");
            } else {
                sql.append("c.association_id=:associationId");
                if (actor.enterpriseId() != null) {
                    sql.append(" AND c.enterprise_id=:enterpriseId");
                }
            }
        } else if (actor.isAssociationStaff()) {
            sql.append("c.association_id=:associationId");
        } else if (actor.associationId() != null) {
            sql.append("c.association_id=:associationId")
                    .append(" AND (c.enterprise_id=:enterpriseId")
                    .append(" OR (c.enterprise_id IS NULL AND c.match_id IS NULL)")
                    .append(" OR EXISTS (SELECT 1 FROM ecosystem_match sm")
                    .append(" WHERE sm.id=c.match_id AND sm.deleted_at IS NULL")
                    .append(" AND (sm.candidate_enterprise_id=:enterpriseId")
                    .append(" OR EXISTS (SELECT 1 FROM cooperation_demand sd")
                    .append(" WHERE sd.id=sm.demand_id AND sd.enterprise_id=:enterpriseId)")
                    .append(")))");
        } else {
            sql.append("FALSE");
        }
        if (!includeDeleted) {
            sql.append(" AND c.deleted_at IS NULL");
        }
        if (!actor.isSystemAdmin() && !actor.isAssociationStaff()) {
            sql.append(" AND (c.enterprise_id IS NULL OR EXISTS (")
                    .append("SELECT 1 FROM enterprise oe WHERE oe.id=c.enterprise_id ")
                    .append("AND oe.status='ACTIVE' AND oe.deleted_at IS NULL))")
                    .append(" AND (c.match_id IS NULL OR EXISTS (")
                    .append("SELECT 1 FROM ecosystem_match lm ")
                    .append("JOIN cooperation_demand ld ON ld.id=lm.demand_id ")
                    .append("JOIN enterprise lde ON lde.id=ld.enterprise_id ")
                    .append("JOIN enterprise lce ON lce.id=lm.candidate_enterprise_id ")
                    .append("WHERE lm.id=c.match_id AND lm.deleted_at IS NULL AND ld.deleted_at IS NULL ")
                    .append("AND lde.status='ACTIVE' AND lde.deleted_at IS NULL ")
                    .append("AND lce.status='ACTIVE' AND lce.deleted_at IS NULL))");
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (lower(c.title) LIKE :query")
                    .append(" OR lower(coalesce(c.owner_subject,'')) LIKE :query")
                    .append(" OR lower(c.participants::text) LIKE :query)");
        }
        return sql.toString();
    }

    private MapSqlParameterSource params(ActorScope actor, String query) {
        return new MapSqlParameterSource().addValue("associationId", actor.associationId())
                .addValue("enterpriseId", actor.enterpriseId())
                .addValue("query", query == null ? null : "%" + query.trim().toLowerCase() + "%");
    }

    private MapSqlParameterSource valueParams(CollaborationUpsertRequest request, ActorScope actor) {
        return new MapSqlParameterSource().addValue("title", request.title().trim())
                .addValue("participants", json(cleanList(request.participants())))
                .addValue("owner", owner(request.owner(), actor)).addValue("priority", priority(request.priority()))
                .addValue("nextAction", clean(request.nextAction()))
                .addValue("matchId", request.matchId())
                .addValue("dueAt", request.dueDate() == null ? null
                        : Timestamp.from(request.dueDate().atStartOfDay(ZoneOffset.UTC).toInstant()))
                .addValue("progress", request.progress() == null ? 0 : request.progress());
    }

    private CollaborationView map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp dueAt = rs.getTimestamp("due_at");
        return new CollaborationView(
                rs.getObject("id", UUID.class), rs.getObject("association_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class), rs.getObject("match_id", UUID.class),
                rs.getString("title"),
                readList(rs.getString("participants")), rs.getString("owner"), rs.getString("status"),
                rs.getString("priority"), rs.getString("next_action"),
                dueAt == null ? null : dueAt.toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                rs.getInt("progress"), rs.getLong("version"), rs.getTimestamp("disabled_at") != null,
                rs.getTimestamp("deleted_at") != null, rs.getTimestamp("updated_at").toInstant());
    }

    private CollaborationActivityView mapActivity(ResultSet rs, int rowNum) throws SQLException {
        return new CollaborationActivityView(rs.getLong("id"), rs.getString("activity_type"),
                rs.getString("detail"), rs.getString("actor_subject"),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private CollaborationHistoryView mapHistory(ResultSet rs, int rowNum) throws SQLException {
        return new CollaborationHistoryView(rs.getLong("id"), rs.getLong("resource_version"),
                rs.getString("action"), rs.getString("actor_subject"),
                readMap(rs.getString("snapshot")), rs.getTimestamp("occurred_at").toInstant());
    }

    private List<String> readList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored collaboration participants are invalid JSON", exception);
        }
    }

    private Map<String, Object> readMap(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored collaboration history is invalid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize collaboration value", exception);
        }
    }

    private static List<String> cleanList(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String owner(String value, ActorScope actor) {
        String cleaned = clean(value);
        if (cleaned != null) return cleaned;
        return actor.username() == null || actor.username().isBlank() ? actor.subject() : actor.username();
    }

    private static String priority(String value) {
        return value == null || value.isBlank() ? "MEDIUM" : value.trim().toUpperCase();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
