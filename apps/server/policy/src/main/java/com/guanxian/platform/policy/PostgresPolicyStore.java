package com.guanxian.platform.policy;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresPolicyStore implements PolicyStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String SELECT = """
            SELECT p.id, p.title, p.issuing_authority, p.document_number, p.policy_level, p.category,
                   p.published_on, p.effective_on, p.source_url, p.status, p.summary,
                   p.tags::text AS tags, p.association_id, p.visibility, p.version,
                   p.disabled_at, p.deleted_at, p.updated_at
              FROM policy_document p
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<PolicyView> mapper = this::mapPolicy;

    PostgresPolicyStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PolicyView> list(
            ActorScope actor, String query, String level,
            boolean includeDeleted, int offset, int limit) {
        MapSqlParameterSource params = parameters(actor, query, level)
                .addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(SELECT + where(actor, query, level, includeDeleted)
                        + " ORDER BY p.updated_at DESC, p.id LIMIT :limit OFFSET :offset",
                params, mapper);
    }

    @Override
    public long count(ActorScope actor, String query, String level, boolean includeDeleted) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM policy_document p"
                + where(actor, query, level, includeDeleted),
                parameters(actor, query, level), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public List<String> levels(ActorScope actor) {
        return jdbc.queryForList("SELECT DISTINCT p.policy_level FROM policy_document p"
                + where(actor, null, null, false)
                + " AND p.policy_level IS NOT NULL AND btrim(p.policy_level)<>''"
                + " ORDER BY p.policy_level",
                parameters(actor, null, null), String.class);
    }

    @Override
    public Optional<PolicyView> find(UUID id, ActorScope actor, boolean includeDeleted) {
        MapSqlParameterSource params = parameters(actor, null, null).addValue("id", id);
        return jdbc.query(SELECT + where(actor, null, null, includeDeleted) + " AND p.id = :id", params, mapper)
                .stream().findFirst();
    }

    @Override
    public PolicyView create(UUID associationId, PolicyUpsertRequest request, ActorScope actor) {
        return jdbc.queryForObject("""
                INSERT INTO policy_document (
                    association_id, title, issuing_authority, document_number, policy_level, category,
                    published_on, effective_on, source_url, summary, tags, visibility, status,
                    created_by_subject, updated_by_subject)
                VALUES (
                    :associationId, :title, :authority, :documentNumber, :level, :category,
                    :publishDate, :effectiveDate, :sourceUrl, :summary, CAST(:tags AS jsonb),
                    :visibility, 'DRAFT', :subject, :subject)
                RETURNING id, title, issuing_authority, document_number, policy_level, category,
                          published_on, effective_on, source_url, status, summary, tags::text AS tags,
                          association_id, visibility, version, disabled_at, deleted_at, updated_at
                """, requestParams(request, actor)
                .addValue("associationId", associationId), mapper);
    }

    @Override
    public Optional<PolicyView> update(
            UUID id, long expectedVersion, PolicyUpsertRequest request, ActorScope actor) {
        return jdbc.query("""
                UPDATE policy_document
                   SET title = :title, issuing_authority = :authority, document_number = :documentNumber,
                       policy_level = :level, category = :category, published_on = :publishDate,
                       effective_on = :effectiveDate, source_url = :sourceUrl, summary = :summary,
                       tags = CAST(:tags AS jsonb), visibility = :visibility, status = 'DRAFT',
                       disabled_at = NULL, approved_by_subject = NULL, approved_at = NULL,
                       updated_by_subject = :subject, updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version AND deleted_at IS NULL
                RETURNING id, title, issuing_authority, document_number, policy_level, category,
                          published_on, effective_on, source_url, status, summary, tags::text AS tags,
                          association_id, visibility, version, disabled_at, deleted_at, updated_at
                """, requestParams(request, actor).addValue("id", id).addValue("version", expectedVersion), mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<PolicyView> transition(
            UUID id, long expectedVersion, String targetStatus, ActorScope actor) {
        return jdbc.query("""
                UPDATE policy_document
                   SET status = :status,
                       disabled_at = CASE WHEN :status = 'DISABLED' THEN now() ELSE NULL END,
                       approved_by_subject = CASE WHEN :status = 'PUBLISHED' THEN :subject ELSE approved_by_subject END,
                       approved_at = CASE WHEN :status = 'PUBLISHED' THEN now() ELSE approved_at END,
                       updated_by_subject = :subject, updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version AND deleted_at IS NULL
                RETURNING id, title, issuing_authority, document_number, policy_level, category,
                          published_on, effective_on, source_url, status, summary, tags::text AS tags,
                          association_id, visibility, version, disabled_at, deleted_at, updated_at
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("version", expectedVersion)
                .addValue("status", targetStatus).addValue("subject", actor.subject()), mapper)
                .stream().findFirst();
    }

    @Override
    public Optional<PolicyView> softDelete(UUID id, long expectedVersion, ActorScope actor) {
        return jdbc.query("""
                UPDATE policy_document
                   SET deleted_at = now(), updated_by_subject = :subject,
                       updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version AND deleted_at IS NULL
                RETURNING id, title, issuing_authority, document_number, policy_level, category,
                          published_on, effective_on, source_url, status, summary, tags::text AS tags,
                          association_id, visibility, version, disabled_at, deleted_at, updated_at
                """, new MapSqlParameterSource().addValue("id", id).addValue("version", expectedVersion)
                .addValue("subject", actor.subject()), mapper).stream().findFirst();
    }

    @Override
    public Optional<PolicyView> restore(UUID id, long expectedVersion, ActorScope actor) {
        return jdbc.query("""
                UPDATE policy_document
                   SET deleted_at = NULL, disabled_at = NULL, status = 'DRAFT',
                       approved_by_subject = NULL, approved_at = NULL,
                       updated_by_subject = :subject, updated_at = now(), version = version + 1
                 WHERE id = :id AND version = :version AND deleted_at IS NOT NULL
                RETURNING id, title, issuing_authority, document_number, policy_level, category,
                          published_on, effective_on, source_url, status, summary, tags::text AS tags,
                          association_id, visibility, version, disabled_at, deleted_at, updated_at
                """, new MapSqlParameterSource().addValue("id", id).addValue("version", expectedVersion)
                .addValue("subject", actor.subject()), mapper).stream().findFirst();
    }

    @Override
    public void recordChange(ActorScope actor, String action, PolicyView policy, String comment) {
        try {
            Map<String, Object> snapshot = snapshot(policy, comment);
            String json = objectMapper.writeValueAsString(snapshot);
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("actorUserId", actor.userId()).addValue("actorSubject", actor.subject())
                    .addValue("actorUsername", actor.username()).addValue("associationId", policy.associationId())
                    .addValue("action", action).addValue("resourceId", policy.id())
                    .addValue("version", policy.version()).addValue("snapshot", json)
                    .addValue("requestId", MDC.get("requestId"));
            jdbc.update("""
                    INSERT INTO audit_log (
                        actor_user_id, actor_subject, actor_username, association_id, enterprise_id, action,
                        resource_type, resource_id, resource_version, outcome, details, request_id)
                    VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                            :actorSubject, COALESCE(:actorUsername, :actorSubject), :associationId,
                            NULL, :action, 'POLICY_DOCUMENT', :resourceId, :version, 'SUCCESS',
                            CAST(:snapshot AS jsonb), COALESCE(:requestId, 'internal'))
                    """, params);
            jdbc.update("""
                    INSERT INTO business_entity_history (
                        association_id, resource_type, resource_id, resource_version,
                        action, actor_subject, snapshot)
                    VALUES (:associationId, 'POLICY_DOCUMENT', CAST(:resourceId AS uuid), :version,
                            :action, :actorSubject, CAST(:snapshot AS jsonb))
                    """, params);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("policy history could not be serialized", exception);
        }
    }

    @Override
    public List<PolicyHistoryView> history(UUID id, ActorScope actor, int limit) {
        return jdbc.query("""
                SELECT resource_version, action, actor_subject, snapshot::text AS snapshot, occurred_at
                  FROM business_entity_history
                 WHERE resource_type = 'POLICY_DOCUMENT' AND resource_id = :id
                 ORDER BY resource_version DESC, occurred_at DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("id", id).addValue("limit", limit),
                (rs, row) -> new PolicyHistoryView(rs.getLong("resource_version"), rs.getString("action"),
                        rs.getString("actor_subject"), readMap(rs.getString("snapshot")),
                        rs.getTimestamp("occurred_at").toInstant()));
    }

    private MapSqlParameterSource requestParams(PolicyUpsertRequest request, ActorScope actor) {
        try {
            return new MapSqlParameterSource()
                    .addValue("title", request.title().trim()).addValue("authority", clean(request.authority()))
                    .addValue("documentNumber", clean(request.documentNumber())).addValue("level", clean(request.level()))
                    .addValue("category", clean(request.category())).addValue("publishDate", request.publishDate())
                    .addValue("effectiveDate", request.effectiveDate()).addValue("sourceUrl", clean(request.sourceUrl()))
                    .addValue("summary", clean(request.summary()))
                    .addValue("tags", objectMapper.writeValueAsString(list(request.tags())))
                    .addValue("visibility", visibility(request.visibility())).addValue("subject", actor.subject());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("policy tags could not be serialized", exception);
        }
    }

    private MapSqlParameterSource parameters(ActorScope actor, String query, String level) {
        List<UUID> partners = actor.partnerAssociationIds().isEmpty()
                ? List.of(new UUID(0, 0)) : List.copyOf(actor.partnerAssociationIds());
        return new MapSqlParameterSource().addValue("associationId", actor.associationId())
                .addValue("associationStaff", actor.isAssociationStaff())
                .addValue("partnerIds", partners)
                .addValue("query", query == null ? null : "%" + query.trim() + "%")
                .addValue("level", level);
    }

    private String where(ActorScope actor, String query, String level, boolean includeDeleted) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (!includeDeleted) {
            sql.append(" AND p.deleted_at IS NULL");
        }
        if (!actor.isSystemAdmin()) {
            sql.append("""
                     AND (
                       (p.association_id = :associationId AND (:associationStaff OR
                          (p.status = 'PUBLISHED' AND p.disabled_at IS NULL)))
                       OR (p.status = 'PUBLISHED' AND p.disabled_at IS NULL AND p.visibility = 'PUBLIC')
                       OR (p.status = 'PUBLISHED' AND p.disabled_at IS NULL
                           AND p.visibility = 'PARTNERS' AND p.association_id IN (:partnerIds))
                     )
                    """);
        }
        if (query != null && !query.isBlank()) {
            sql.append("""
                     AND (p.title ILIKE :query OR p.issuing_authority ILIKE :query
                          OR p.category ILIKE :query OR p.summary ILIKE :query
                          OR p.tags::text ILIKE :query)
                    """);
        }
        if (level != null && !level.isBlank()) {
            sql.append(" AND p.policy_level = :level");
        }
        return sql.toString();
    }

    private PolicyView mapPolicy(ResultSet rs, int row) throws SQLException {
        return new PolicyView(rs.getObject("id", UUID.class).toString(), rs.getString("title"),
                rs.getString("issuing_authority"), rs.getString("document_number"),
                rs.getString("policy_level"), rs.getString("category"),
                date(rs, "published_on"), date(rs, "effective_on"), rs.getString("source_url"),
                rs.getString("status"), rs.getString("summary"), readList(rs.getString("tags")),
                rs.getObject("association_id", UUID.class), rs.getString("visibility"), rs.getLong("version"),
                rs.getTimestamp("disabled_at") != null, rs.getTimestamp("deleted_at") != null,
                rs.getTimestamp("updated_at").toInstant());
    }

    private List<String> readList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored policy tags are invalid JSON", exception);
        }
    }

    private Map<String, Object> readMap(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored policy history is invalid JSON", exception);
        }
    }

    private static Map<String, Object> snapshot(PolicyView policy, String comment) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("id", policy.id());
        values.put("title", policy.title());
        values.put("status", policy.status());
        values.put("visibility", policy.visibility());
        values.put("version", policy.version());
        values.put("disabled", policy.disabled());
        values.put("deleted", policy.deleted());
        if (comment != null && !comment.isBlank()) {
            values.put("comment", comment.trim());
        }
        return Map.copyOf(values);
    }

    private static java.time.LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String visibility(String value) {
        return value == null || value.isBlank() ? "MEMBERS" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
