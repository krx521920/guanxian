package com.guanxian.platform.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresAttachmentMetadataStore implements AttachmentMetadataStore {
    private static final String COLUMNS = """
            id, association_id, enterprise_id, bucket_name, object_key, original_filename,
            media_type, size_bytes, sha256, scan_status, visibility, lifecycle_status,
            version, uploaded_by_subject, uploaded_at, updated_at, deleted_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    PostgresAttachmentMetadataStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AttachmentView create(AttachmentDraft draft, ActorScope actor) {
        jdbc.update("""
                INSERT INTO object_file (
                    id, association_id, enterprise_id, bucket_name, object_key, original_filename,
                    media_type, size_bytes, sha256, scan_status, visibility, lifecycle_status,
                    version, uploaded_by_subject, uploaded_at, updated_at)
                VALUES (
                    :id, :associationId, :enterpriseId, :bucketName, :objectKey, :originalFilename,
                    :mediaType, :sizeBytes, :sha256, 'PENDING', :visibility, 'ACTIVE',
                    0, :uploadedBy, now(), now())
                """, draftParameters(draft));
        AttachmentView created = findById(draft.id()).orElseThrow();
        audit(actor, "FILE_UPLOAD", created);
        return created;
    }

    @Override
    public Optional<AttachmentView> findVisible(UUID id, ActorScope actor, boolean includeDeleted) {
        MapSqlParameterSource parameters = scopeParameters(actor)
                .addValue("id", id);
        return jdbc.query("""
                SELECT %s FROM object_file
                WHERE id = :id %s %s
                """.formatted(COLUMNS, deletedClause(includeDeleted), readScope(actor)),
                parameters, this::map).stream().findFirst();
    }

    @Override
    public List<AttachmentView> listVisible(
            ActorScope actor, UUID enterpriseId, boolean includeDeleted, int offset, int limit) {
        MapSqlParameterSource parameters = scopeParameters(actor)
                .addValue("targetEnterpriseId", enterpriseId)
                .addValue("offset", offset)
                .addValue("limit", limit);
        String enterpriseClause = enterpriseId == null ? "" : " AND enterprise_id = :targetEnterpriseId";
        return jdbc.query("""
                SELECT %s FROM object_file
                WHERE 1 = 1 %s %s %s
                ORDER BY uploaded_at DESC, id
                OFFSET :offset LIMIT :limit
                """.formatted(COLUMNS, deletedClause(includeDeleted), enterpriseClause, readScope(actor)),
                parameters, this::map);
    }

    @Override
    public long countVisible(ActorScope actor, UUID enterpriseId, boolean includeDeleted) {
        MapSqlParameterSource parameters = scopeParameters(actor)
                .addValue("targetEnterpriseId", enterpriseId);
        String enterpriseClause = enterpriseId == null ? "" : " AND enterprise_id = :targetEnterpriseId";
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM object_file
                WHERE 1 = 1 %s %s %s
                """.formatted(deletedClause(includeDeleted), enterpriseClause, readScope(actor)),
                parameters, Long.class);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public Optional<AttachmentView> softDelete(UUID id, long expectedVersion, ActorScope actor) {
        return transition(id, expectedVersion, actor, false);
    }

    @Override
    @Transactional
    public Optional<AttachmentView> restore(UUID id, long expectedVersion, ActorScope actor) {
        return transition(id, expectedVersion, actor, true);
    }

    private Optional<AttachmentView> transition(
            UUID id, long expectedVersion, ActorScope actor, boolean restore) {
        MapSqlParameterSource parameters = scopeParameters(actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("actorSubject", actor.subject());
        String deletedPredicate = restore ? "deleted_at IS NOT NULL" : "deleted_at IS NULL";
        String update = restore
                ? "lifecycle_status = 'ACTIVE', deleted_at = NULL, deleted_by_subject = NULL"
                : "lifecycle_status = 'DELETED', deleted_at = now(), deleted_by_subject = :actorSubject";
        int changed = jdbc.update("""
                UPDATE object_file SET %s, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :expectedVersion AND %s %s
                """.formatted(update, deletedPredicate, manageScope(actor)), parameters);
        if (changed == 0) {
            return Optional.empty();
        }
        AttachmentView value = findById(id).orElseThrow();
        audit(actor, restore ? "FILE_RESTORE" : "FILE_DELETE", value);
        return Optional.of(value);
    }

    private Optional<AttachmentView> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM object_file WHERE id = :id",
                new MapSqlParameterSource("id", id), this::map).stream().findFirst();
    }

    private String readScope(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return "";
        }
        if (actor.isAssociationStaff()) {
            return " AND association_id = :actorAssociationId";
        }
        return """
                 AND association_id = :actorAssociationId
                 AND (visibility = 'ASSOCIATION'
                      OR (:actorEnterpriseId IS NOT NULL AND enterprise_id = :actorEnterpriseId))
                """;
    }

    private String manageScope(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return "";
        }
        if (actor.isAssociationStaff()) {
            return " AND association_id = :actorAssociationId";
        }
        return """
                 AND association_id = :actorAssociationId
                 AND enterprise_id = :actorEnterpriseId
                """;
    }

    private MapSqlParameterSource scopeParameters(ActorScope actor) {
        return new MapSqlParameterSource()
                .addValue("actorAssociationId", actor.associationId())
                .addValue("actorEnterpriseId", actor.enterpriseId());
    }

    private MapSqlParameterSource draftParameters(AttachmentDraft draft) {
        return new MapSqlParameterSource()
                .addValue("id", draft.id())
                .addValue("associationId", draft.associationId())
                .addValue("enterpriseId", draft.enterpriseId())
                .addValue("bucketName", draft.bucketName())
                .addValue("objectKey", draft.objectKey())
                .addValue("originalFilename", draft.originalFilename())
                .addValue("mediaType", draft.mediaType())
                .addValue("sizeBytes", draft.sizeBytes())
                .addValue("sha256", draft.sha256())
                .addValue("visibility", draft.visibility())
                .addValue("uploadedBy", draft.uploadedBySubject());
    }

    private AttachmentView map(ResultSet rs, int row) throws SQLException {
        return new AttachmentView(
                rs.getObject("id", UUID.class),
                rs.getObject("association_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("bucket_name"),
                rs.getString("object_key"),
                rs.getString("original_filename"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("scan_status"),
                rs.getString("visibility"),
                rs.getString("lifecycle_status"),
                rs.getLong("version"),
                rs.getString("uploaded_by_subject"),
                instant(rs, "uploaded_at"),
                instant(rs, "updated_at"),
                instant(rs, "deleted_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String deletedClause(boolean includeDeleted) {
        return includeDeleted ? "" : " AND deleted_at IS NULL";
    }

    private void audit(ActorScope actor, String action, AttachmentView value) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "filename", value.originalFilename(),
                    "mediaType", value.mediaType(),
                    "sizeBytes", value.sizeBytes(),
                    "version", value.version(),
                    "status", value.status()));
            jdbc.update("""
                    INSERT INTO audit_log (
                        actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                        action, resource_type, resource_id, details, request_id)
                    VALUES (
                        :actorUserId, :actorSubject, :actorUsername, :associationId, :enterpriseId,
                        :action, 'OBJECT_FILE', :resourceId, CAST(:details AS jsonb), :requestId)
                    """, new MapSqlParameterSource()
                    .addValue("actorUserId", actor.userId())
                    .addValue("actorSubject", actor.subject())
                    .addValue("actorUsername", actor.username())
                    .addValue("associationId", value.associationId())
                    .addValue("enterpriseId", value.enterpriseId())
                    .addValue("action", action)
                    .addValue("resourceId", value.id().toString())
                    .addValue("details", details)
                    .addValue("requestId", MDC.get("requestId")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("attachment audit details could not be serialized", exception);
        }
    }
}
