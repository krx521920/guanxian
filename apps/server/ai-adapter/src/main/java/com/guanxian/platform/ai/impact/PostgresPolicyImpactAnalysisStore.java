package com.guanxian.platform.ai.impact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisDraft;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisSource;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ImpactActor;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ReadScope;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.SourceChunk;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class PostgresPolicyImpactAnalysisStore implements PolicyImpactAnalysisStore {
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String SELECT = """
            SELECT analysis.id, analysis.policy_document_id, policy.title AS policy_title,
                   analysis.enterprise_id, enterprise.name AS enterprise_name, enterprise.association_id,
                   analysis.impact_level, analysis.summary, analysis.evidence_chunk_ids::text AS evidence_chunk_ids,
                   analysis.status, analysis.model_execution_id, analysis.reviewed_by_subject,
                   analysis.reviewed_at, analysis.version, analysis.created_at, analysis.updated_at
              FROM policy_impact_analysis analysis
              JOIN policy_document policy ON policy.id = analysis.policy_document_id
              JOIN enterprise enterprise ON enterprise.id = analysis.enterprise_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<PolicyImpactAnalysisView> mapper = this::mapAnalysis;

    public PostgresPolicyImpactAnalysisStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AnalysisSource> loadSource(UUID policyDocumentId, UUID enterpriseId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("policyDocumentId", policyDocumentId)
                .addValue("enterpriseId", enterpriseId);
        List<AnalysisSourceHeader> headers = jdbc.query("""
                SELECT policy.id AS policy_id, policy.title AS policy_title, policy.source_url,
                       enterprise.id AS enterprise_id, enterprise.name AS enterprise_name,
                       enterprise.association_id,
                       CONCAT_WS(' ', enterprise.name, enterprise.category, enterprise.description,
                           enterprise.enterprise_roles::text, enterprise.service_scenarios::text,
                           enterprise.capabilities::text, enterprise.products::text,
                           enterprise.cooperation_needs::text) AS enterprise_profile
                  FROM policy_document policy
                  JOIN enterprise enterprise ON enterprise.id = :enterpriseId
                 WHERE policy.id = :policyDocumentId
                   AND policy.association_id = enterprise.association_id
                   AND policy.status = 'PUBLISHED'
                   AND policy.disabled_at IS NULL
                   AND policy.deleted_at IS NULL
                   AND enterprise.status = 'ACTIVE'
                   AND enterprise.deleted_at IS NULL
                """, params, (rs, row) -> new AnalysisSourceHeader(
                rs.getObject("policy_id", UUID.class), rs.getString("policy_title"), rs.getString("source_url"),
                rs.getObject("enterprise_id", UUID.class), rs.getString("enterprise_name"),
                rs.getObject("association_id", UUID.class), rs.getString("enterprise_profile")));
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        AnalysisSourceHeader header = headers.getFirst();
        params.addValue("associationId", header.associationId())
                .addValue("policyTitle", header.policyTitle())
                .addValue("sourceUrl", header.sourceUrl());
        List<SourceChunk> chunks = jdbc.query("""
                SELECT chunk.id, chunk.content
                  FROM knowledge_chunk chunk
                  JOIN knowledge_document_version document_version
                    ON document_version.id = chunk.document_version_id
                  JOIN knowledge_document document ON document.id = document_version.document_id
                 WHERE document.association_id = :associationId
                   AND document.document_type = 'POLICY'
                   AND document.status = 'PUBLISHED'
                   AND document.deleted_at IS NULL
                   AND document_version.status = 'READY'
                   AND document_version.version = document.current_version
                   AND (
                       LOWER(BTRIM(document.title)) = LOWER(BTRIM(:policyTitle))
                       OR (CAST(:sourceUrl AS TEXT) IS NOT NULL AND document.source_url = :sourceUrl)
                   )
                 ORDER BY document.updated_at DESC, chunk.chunk_index
                 LIMIT 200
                """, params, (rs, row) -> new SourceChunk(
                rs.getObject("id", UUID.class), rs.getString("content")));
        return Optional.of(new AnalysisSource(
                header.policyDocumentId(), header.policyTitle(), header.enterpriseId(), header.enterpriseName(),
                header.associationId(), header.enterpriseProfile(), chunks));
    }

    @Override
    public Optional<PolicyImpactAnalysisView> find(UUID id) {
        return jdbc.query(SELECT + " WHERE analysis.id = :id",
                new MapSqlParameterSource("id", id), mapper).stream().findFirst();
    }

    @Override
    public Optional<PolicyImpactAnalysisView> findByPair(UUID policyDocumentId, UUID enterpriseId) {
        return jdbc.query(SELECT + " WHERE analysis.policy_document_id = :policyDocumentId"
                        + " AND analysis.enterprise_id = :enterpriseId",
                new MapSqlParameterSource("policyDocumentId", policyDocumentId)
                        .addValue("enterpriseId", enterpriseId), mapper).stream().findFirst();
    }

    @Override
    public List<PolicyImpactAnalysisView> list(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId, long offset, int limit) {
        MapSqlParameterSource params = filters(scope, status, policyDocumentId, enterpriseId)
                .addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(SELECT + where(scope, status, policyDocumentId, enterpriseId)
                + " ORDER BY analysis.updated_at DESC, analysis.id LIMIT :limit OFFSET :offset", params, mapper);
    }

    @Override
    public long count(ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM policy_impact_analysis analysis
                  JOIN enterprise enterprise ON enterprise.id = analysis.enterprise_id
                """ + where(scope, status, policyDocumentId, enterpriseId),
                filters(scope, status, policyDocumentId, enterpriseId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public PolicyImpactAnalysisView create(AnalysisDraft draft) {
        requireOperational(draft.enterpriseId());
        try {
            return jdbc.query("""
                    INSERT INTO policy_impact_analysis (
                        policy_document_id, enterprise_id, impact_level, summary,
                        evidence_chunk_ids, status, version)
                    VALUES (:policyDocumentId, :enterpriseId, :impactLevel, :summary,
                            CAST(:evidenceChunkIds AS jsonb), 'PENDING_REVIEW', 0)
                    RETURNING id
                    """, draftParams(draft), (rs, row) -> rs.getObject("id", UUID.class)).stream()
                    .findFirst().flatMap(this::find).orElseThrow();
        } catch (DuplicateKeyException exception) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.CONFLICT,
                    "policy impact analysis already exists for this enterprise");
        }
    }

    @Override
    public Optional<PolicyImpactAnalysisView> reanalyze(
            UUID id, long expectedVersion, AnalysisDraft draft) {
        MapSqlParameterSource params = draftParams(draft)
                .addValue("id", id).addValue("version", expectedVersion);
        return jdbc.query("""
                UPDATE policy_impact_analysis
                   SET impact_level = :impactLevel,
                       summary = :summary,
                       evidence_chunk_ids = CAST(:evidenceChunkIds AS jsonb),
                       status = 'PENDING_REVIEW', model_execution_id = NULL,
                       reviewed_by_subject = NULL, reviewed_at = NULL,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :version
                   AND EXISTS (
                       SELECT 1 FROM enterprise write_enterprise
                        WHERE write_enterprise.id=policy_impact_analysis.enterprise_id
                          AND write_enterprise.status='ACTIVE'
                          AND write_enterprise.deleted_at IS NULL)
                 RETURNING id
                """, params, (rs, row) -> rs.getObject("id", UUID.class)).stream()
                .findFirst().flatMap(this::find);
    }

    @Override
    public Optional<PolicyImpactAnalysisView> review(
            UUID id, long expectedVersion, String targetStatus, String reviewerSubject) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("version", expectedVersion).addValue("status", targetStatus)
                .addValue("reviewerSubject", reviewerSubject);
        return jdbc.query("""
                UPDATE policy_impact_analysis
                   SET status = :status, reviewed_by_subject = :reviewerSubject,
                       reviewed_at = now(), version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :version
                   AND EXISTS (
                       SELECT 1 FROM enterprise write_enterprise
                        WHERE write_enterprise.id=policy_impact_analysis.enterprise_id
                          AND write_enterprise.status='ACTIVE'
                          AND write_enterprise.deleted_at IS NULL)
                 RETURNING id
                """, params, (rs, row) -> rs.getObject("id", UUID.class)).stream()
                .findFirst().flatMap(this::find);
    }

    @Override
    public void recordChange(
            ImpactActor actor, String action, PolicyImpactAnalysisView value, String comment) {
        try {
            String snapshot = objectMapper.writeValueAsString(snapshot(value, comment));
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("actorUserId", actor.userId()).addValue("actorSubject", actor.subject())
                    .addValue("actorUsername", actor.username()).addValue("associationId", value.associationId())
                    .addValue("enterpriseId", value.enterpriseId()).addValue("action", action)
                    .addValue("resourceId", value.id()).addValue("version", value.version())
                    .addValue("snapshot", snapshot).addValue("requestId", MDC.get("requestId"));
            jdbc.update("""
                    INSERT INTO audit_log (
                        actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                        action, resource_type, resource_id, resource_version, outcome, details, request_id)
                    VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                            :actorSubject, COALESCE(:actorUsername, :actorSubject), :associationId, :enterpriseId,
                            :action, 'POLICY_IMPACT_ANALYSIS', CAST(:resourceId AS varchar),
                            :version, 'SUCCESS', CAST(:snapshot AS jsonb), COALESCE(:requestId, 'internal'))
                    """, params);
            jdbc.update("""
                    INSERT INTO business_entity_history (
                        association_id, enterprise_id, resource_type, resource_id,
                        resource_version, action, actor_subject, snapshot)
                    VALUES (:associationId, :enterpriseId, 'POLICY_IMPACT_ANALYSIS', :resourceId,
                            :version, :action, :actorSubject, CAST(:snapshot AS jsonb))
                    """, params);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("policy impact history could not be serialized", exception);
        }
    }

    @Override
    public List<PolicyImpactHistoryView> history(UUID id, int limit) {
        return jdbc.query("""
                SELECT resource_version, action, actor_subject, snapshot::text AS snapshot, occurred_at
                  FROM business_entity_history
                 WHERE resource_type = 'POLICY_IMPACT_ANALYSIS' AND resource_id = :id
                 ORDER BY resource_version DESC, occurred_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("id", id).addValue("limit", limit),
                (rs, row) -> new PolicyImpactHistoryView(
                        rs.getLong("resource_version"), rs.getString("action"), rs.getString("actor_subject"),
                        readMap(rs.getString("snapshot")), rs.getTimestamp("occurred_at").toInstant()));
    }

    private PolicyImpactAnalysisView mapAnalysis(ResultSet rs, int row) throws SQLException {
        return new PolicyImpactAnalysisView(
                rs.getObject("id", UUID.class), rs.getObject("policy_document_id", UUID.class),
                rs.getString("policy_title"), rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"), rs.getObject("association_id", UUID.class),
                rs.getString("impact_level"), rs.getString("summary"),
                readUuidList(rs.getString("evidence_chunk_ids")), rs.getString("status"),
                rs.getObject("model_execution_id", UUID.class), rs.getString("reviewed_by_subject"),
                instant(rs.getTimestamp("reviewed_at")), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), null);
    }

    private MapSqlParameterSource draftParams(AnalysisDraft draft) {
        return new MapSqlParameterSource()
                .addValue("policyDocumentId", draft.policyDocumentId())
                .addValue("enterpriseId", draft.enterpriseId())
                .addValue("impactLevel", draft.impactLevel())
                .addValue("summary", draft.summary())
                .addValue("evidenceChunkIds", writeJson(draft.evidenceChunkIds()));
    }

    private static MapSqlParameterSource filters(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId) {
        return new MapSqlParameterSource()
                .addValue("associationId", scope.associationId())
                .addValue("scopeEnterpriseId", scope.enterpriseId())
                .addValue("status", status)
                .addValue("policyDocumentId", policyDocumentId)
                .addValue("enterpriseId", enterpriseId);
    }

    private static String where(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId) {
        List<String> clauses = new ArrayList<>();
        if (!scope.systemAdmin() && !scope.associationStaff()) {
            clauses.add("enterprise.status = 'ACTIVE'");
            clauses.add("enterprise.deleted_at IS NULL");
        }
        if (scope.systemAdmin()) {
            if (scope.enterpriseId() != null) {
                clauses.add("analysis.enterprise_id = :scopeEnterpriseId");
            }
            if (scope.associationId() != null) {
                clauses.add("enterprise.association_id = :associationId");
            }
        } else {
            if (scope.enterpriseId() != null) {
                clauses.add("analysis.enterprise_id = :scopeEnterpriseId");
            } else if (scope.associationStaff() && scope.associationId() != null) {
                clauses.add("enterprise.association_id = :associationId");
            } else {
                clauses.add("FALSE");
            }
        }
        if (status != null) clauses.add("analysis.status = :status");
        if (policyDocumentId != null) clauses.add("analysis.policy_document_id = :policyDocumentId");
        if (enterpriseId != null) clauses.add("analysis.enterprise_id = :enterpriseId");
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private Map<String, Object> snapshot(PolicyImpactAnalysisView value, String comment) {
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("id", value.id());
        snapshot.put("policyDocumentId", value.policyDocumentId());
        snapshot.put("enterpriseId", value.enterpriseId());
        snapshot.put("impactLevel", value.impactLevel());
        snapshot.put("summary", value.summary());
        snapshot.put("evidenceChunkIds", value.evidenceChunkIds());
        snapshot.put("status", value.status());
        snapshot.put("version", value.version());
        snapshot.put("analysisMethod", value.analysisMethod());
        if (value.reviewedBySubject() != null) snapshot.put("reviewedBySubject", value.reviewedBySubject());
        if (comment != null && !comment.isBlank()) snapshot.put("comment", comment.trim());
        return Map.copyOf(snapshot);
    }

    private void requireOperational(UUID enterpriseId) {
        Boolean operational = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM enterprise
                     WHERE id=:enterpriseId
                       AND status='ACTIVE'
                       AND deleted_at IS NULL)
                """, new MapSqlParameterSource("enterpriseId", enterpriseId), Boolean.class);
        if (!Boolean.TRUE.equals(operational)) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.PRECONDITION_FAILED,
                    "enterprise must be active before policy impact analysis");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("policy impact evidence could not be serialized", exception);
        }
    }

    private List<UUID> readUuidList(String json) {
        try {
            return objectMapper.readValue(json, UUID_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored policy impact evidence is invalid", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored policy impact history is invalid", exception);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record AnalysisSourceHeader(
            UUID policyDocumentId,
            String policyTitle,
            String sourceUrl,
            UUID enterpriseId,
            String enterpriseName,
            UUID associationId,
            String enterpriseProfile) {
    }
}
