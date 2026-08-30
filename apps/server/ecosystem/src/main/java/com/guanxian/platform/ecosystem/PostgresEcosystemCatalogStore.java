package com.guanxian.platform.ecosystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ForbiddenException;
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
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "guanxian.business.repository",
        havingValue = "postgres",
        matchIfMissing = true)
class PostgresEcosystemCatalogStore implements EcosystemCatalogStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String OFFERING_SELECT = """
            SELECT p.id, p.enterprise_id, e.name AS enterprise_name, p.name, p.kind, p.description,
                   p.scenarios::text AS scenarios, p.qualifications::text AS qualifications,
                   p.visibility, p.status, p.version, p.disabled_at, p.updated_at
              FROM product_service p
              JOIN enterprise e ON e.id = p.enterprise_id
            """;
    private static final String DEMAND_SELECT = """
            SELECT d.id, d.enterprise_id, e.name AS enterprise_name, d.title, d.description,
                   d.scenarios::text AS scenarios,
                   d.required_capabilities::text AS required_capabilities,
                   d.visibility, d.budget_min, d.budget_max, d.response_deadline,
                   d.status, d.close_reason, d.version, d.disabled_at, d.updated_at
              FROM cooperation_demand d
              JOIN enterprise e ON e.id = d.enterprise_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<OfferingView> offeringMapper = this::mapOffering;
    private final RowMapper<DemandView> demandMapper = this::mapDemand;

    PostgresEcosystemCatalogStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<OfferingView> listOfferings(
            ActorScope actor, String query, boolean includeDeleted, int offset, int limit) {
        MapSqlParameterSource params = commonParams(actor, query)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbc.query(OFFERING_SELECT + whereClause(
                        "p", "ACTIVE", actor, query, includeDeleted, params)
                        + " ORDER BY p.updated_at DESC, p.id LIMIT :limit OFFSET :offset",
                params, offeringMapper);
    }

    @Override
    public long countOfferings(ActorScope actor, String query, boolean includeDeleted) {
        MapSqlParameterSource params = commonParams(actor, query);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM product_service p JOIN enterprise e ON e.id=p.enterprise_id "
                        + whereClause("p", "ACTIVE", actor, query, includeDeleted, params),
                params, Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public Optional<OfferingView> findOffering(
            UUID id, ActorScope actor, boolean includeDeleted) {
        MapSqlParameterSource params = commonParams(actor, null).addValue("id", id);
        List<OfferingView> values = jdbc.query(
                OFFERING_SELECT + whereClause("p", "ACTIVE", actor, null, includeDeleted, params)
                        + " AND p.id=:id",
                params, offeringMapper);
        return values.stream().findFirst();
    }

    @Override
    public OfferingView createOffering(
            UUID enterpriseId, OfferingUpsertRequest request, ActorScope actor) {
        requireSystemCreateScope(enterpriseId, actor);
        MapSqlParameterSource params = offeringParams(request)
                .addValue("enterpriseId", enterpriseId)
                .addValue("subject", actor.subject());
        UUID id = jdbc.queryForObject("""
                INSERT INTO product_service (
                    enterprise_id, name, kind, description, scenarios, qualifications,
                    visibility, status, created_by_subject, updated_by_subject)
                VALUES (
                    :enterpriseId, :name, :kind, :description, CAST(:scenarios AS jsonb),
                    CAST(:qualifications AS jsonb), :visibility, 'DRAFT', :subject, :subject)
                RETURNING id
                """, params, UUID.class);
        return findOffering(id, actor, false).orElseThrow();
    }

    @Override
    public Optional<OfferingView> updateOffering(
            UUID id, long expectedVersion, OfferingUpsertRequest request, ActorScope actor) {
        MapSqlParameterSource params = writeParams(offeringParams(request), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject());
        int updated = jdbc.update("""
                UPDATE product_service
                   SET name=:name,
                       kind=:kind,
                       description=:description,
                       scenarios=CAST(:scenarios AS jsonb),
                       qualifications=CAST(:qualifications AS jsonb),
                       visibility=:visibility,
                       status='DRAFT',
                       approved_by_subject=NULL,
                       approved_at=NULL,
                       disabled_at=NULL,
                       version=version+1,
                       updated_by_subject=:subject,
                       updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + systemWriteClause("product_service", actor), params);
        return updated == 0 ? Optional.empty() : findOffering(id, actor, false);
    }

    @Override
    public Optional<OfferingView> transitionOffering(
            UUID id, long expectedVersion, String targetStatus, ActorScope actor) {
        MapSqlParameterSource params = writeParams(new MapSqlParameterSource(), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("targetStatus", targetStatus)
                .addValue("subject", actor.subject());
        int updated = jdbc.update("""
                UPDATE product_service
                   SET status=:targetStatus,
                       disabled_at=CASE WHEN :targetStatus='DISABLED' THEN now() ELSE NULL END,
                       approved_by_subject=CASE
                           WHEN :targetStatus IN ('ACTIVE','REJECTED') THEN :subject
                           ELSE approved_by_subject END,
                       approved_at=CASE
                           WHEN :targetStatus IN ('ACTIVE','REJECTED') THEN now()
                           ELSE approved_at END,
                       version=version+1,
                       updated_by_subject=:subject,
                       updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + systemWriteClause("product_service", actor), params);
        return updated == 0 ? Optional.empty() : findOffering(id, actor, false);
    }

    @Override
    public Optional<OfferingView> softDeleteOffering(
            UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE product_service
                   SET deleted_at=now(), version=version+1,
                       updated_by_subject=:subject, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + systemWriteClause("product_service", actor), writeParams(new MapSqlParameterSource(), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : findOffering(id, actor, true);
    }

    @Override
    public Optional<OfferingView> restoreOffering(
            UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE product_service
                   SET deleted_at=NULL, disabled_at=NULL, status='DRAFT',
                       version=version+1, updated_by_subject=:subject, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NOT NULL
                """ + systemWriteClause("product_service", actor), writeParams(new MapSqlParameterSource(), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : findOffering(id, actor, false);
    }

    @Override
    public List<DemandView> listDemands(
            ActorScope actor, String query, boolean includeDeleted, int offset, int limit) {
        MapSqlParameterSource params = commonParams(actor, query)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbc.query(DEMAND_SELECT + whereClause(
                        "d", "OPEN", actor, query, includeDeleted, params)
                        + " ORDER BY d.updated_at DESC, d.id LIMIT :limit OFFSET :offset",
                params, demandMapper);
    }

    @Override
    public long countDemands(ActorScope actor, String query, boolean includeDeleted) {
        MapSqlParameterSource params = commonParams(actor, query);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM cooperation_demand d JOIN enterprise e ON e.id=d.enterprise_id "
                        + whereClause("d", "OPEN", actor, query, includeDeleted, params),
                params, Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public Optional<DemandView> findDemand(UUID id, ActorScope actor, boolean includeDeleted) {
        MapSqlParameterSource params = commonParams(actor, null).addValue("id", id);
        List<DemandView> values = jdbc.query(
                DEMAND_SELECT + whereClause("d", "OPEN", actor, null, includeDeleted, params)
                        + " AND d.id=:id",
                params, demandMapper);
        return values.stream().findFirst();
    }

    @Override
    public DemandView createDemand(
            UUID enterpriseId, DemandUpsertRequest request, ActorScope actor) {
        requireSystemCreateScope(enterpriseId, actor);
        MapSqlParameterSource params = demandParams(request)
                .addValue("enterpriseId", enterpriseId)
                .addValue("subject", actor.subject());
        UUID id = jdbc.queryForObject("""
                INSERT INTO cooperation_demand (
                    enterprise_id, title, description, scenarios, required_capabilities,
                    visibility, budget_min, budget_max, response_deadline, status,
                    created_by_subject, updated_by_subject)
                VALUES (
                    :enterpriseId, :title, :description, CAST(:scenarios AS jsonb),
                    CAST(:requiredCapabilities AS jsonb), :visibility, :budgetMin, :budgetMax,
                    :responseDeadline, 'DRAFT', :subject, :subject)
                RETURNING id
                """, params, UUID.class);
        return findDemand(id, actor, false).orElseThrow();
    }

    @Override
    public Optional<DemandView> updateDemand(
            UUID id, long expectedVersion, DemandUpsertRequest request, ActorScope actor) {
        MapSqlParameterSource params = writeParams(demandParams(request), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject());
        int updated = jdbc.update("""
                UPDATE cooperation_demand
                   SET title=:title,
                       description=:description,
                       scenarios=CAST(:scenarios AS jsonb),
                       required_capabilities=CAST(:requiredCapabilities AS jsonb),
                       visibility=:visibility,
                       budget_min=:budgetMin,
                       budget_max=:budgetMax,
                       response_deadline=:responseDeadline,
                       status='DRAFT',
                       close_reason=NULL,
                       approved_by_subject=NULL,
                       approved_at=NULL,
                       disabled_at=NULL,
                       version=version+1,
                       updated_by_subject=:subject,
                       updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + systemWriteClause("cooperation_demand", actor), params);
        return updated == 0 ? Optional.empty() : findDemand(id, actor, false);
    }

    @Override
    public Optional<DemandView> transitionDemand(
            UUID id, long expectedVersion, String targetStatus, String reason, ActorScope actor) {
        MapSqlParameterSource params = writeParams(new MapSqlParameterSource(), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("targetStatus", targetStatus)
                .addValue("reason", reason)
                .addValue("subject", actor.subject());
        int updated = jdbc.update("""
                UPDATE cooperation_demand
                   SET status=:targetStatus,
                       close_reason=CASE WHEN :targetStatus IN ('CLOSED','REJECTED') THEN :reason ELSE NULL END,
                       disabled_at=CASE WHEN :targetStatus='DISABLED' THEN now() ELSE NULL END,
                       approved_by_subject=CASE
                           WHEN :targetStatus IN ('OPEN','REJECTED') THEN :subject
                           ELSE approved_by_subject END,
                       approved_at=CASE
                           WHEN :targetStatus IN ('OPEN','REJECTED') THEN now()
                           ELSE approved_at END,
                       version=version+1,
                       updated_by_subject=:subject,
                       updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + systemWriteClause("cooperation_demand", actor), params);
        return updated == 0 ? Optional.empty() : findDemand(id, actor, false);
    }

    @Override
    public Optional<DemandView> softDeleteDemand(
            UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE cooperation_demand
                   SET deleted_at=now(), version=version+1,
                       updated_by_subject=:subject, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + systemWriteClause("cooperation_demand", actor), writeParams(new MapSqlParameterSource(), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : findDemand(id, actor, true);
    }

    @Override
    public Optional<DemandView> restoreDemand(
            UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE cooperation_demand
                   SET deleted_at=NULL, disabled_at=NULL, status='DRAFT', close_reason=NULL,
                       version=version+1, updated_by_subject=:subject, updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NOT NULL
                """ + systemWriteClause("cooperation_demand", actor), writeParams(new MapSqlParameterSource(), actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : findDemand(id, actor, false);
    }

    @Override
    public boolean enterpriseBelongsToAssociation(UUID enterpriseId, UUID associationId) {
        if (enterpriseId == null || associationId == null) {
            return false;
        }
        Boolean belongs = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM enterprise
                    WHERE id=:enterpriseId AND association_id=:associationId
                      AND status='ACTIVE' AND deleted_at IS NULL
                )
                """, new MapSqlParameterSource("enterpriseId", enterpriseId)
                .addValue("associationId", associationId), Boolean.class);
        return Boolean.TRUE.equals(belongs);
    }

    @Override
    public void recordChange(
            ActorScope actor,
            String action,
            String resourceType,
            UUID resourceId,
            UUID associationId,
            UUID enterpriseId,
            long version,
            Object snapshot) {
        String json = json(snapshot);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("associationId", associationId)
                .addValue("enterpriseId", enterpriseId)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("version", version)
                .addValue("action", action)
                .addValue("subject", actor.subject())
                .addValue("actorUserId", actor.userId())
                .addValue("actorUsername", actor.username())
                .addValue("requestId", MDC.get("requestId"))
                .addValue("snapshot", json);
        jdbc.update("""
                INSERT INTO business_entity_history (
                    association_id, enterprise_id, resource_type, resource_id,
                    resource_version, action, actor_subject, snapshot)
                VALUES (
                    :associationId, :enterpriseId, :resourceType, :resourceId,
                    :version, :action, :subject, CAST(:snapshot AS jsonb))
                """, params);
        jdbc.update("""
                INSERT INTO audit_log (
                    actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                    action, resource_type, resource_id, resource_version, outcome, details, request_id)
                VALUES (
                    (SELECT id FROM user_account WHERE id = :actorUserId),
                    :subject, COALESCE(:actorUsername, :subject), :associationId, :enterpriseId,
                    :action, :resourceType, CAST(:resourceId AS varchar), :version, 'SUCCESS',
                    CAST(:snapshot AS jsonb), COALESCE(:requestId, 'internal'))
                """, params);
    }

    private String whereClause(
            String alias,
            String publishedStatus,
            ActorScope actor,
            String query,
            boolean includeDeleted,
            MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" WHERE ");
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                sql.append(actor.enterpriseId() == null ? "TRUE" : "FALSE");
            } else if (actor.enterpriseId() != null) {
                sql.append("e.association_id=:associationId AND ")
                        .append(alias).append(".enterprise_id=:enterpriseId");
            } else {
                sql.append("e.association_id=:associationId");
            }
        } else if (actor.isAssociationStaff()) {
            sql.append("(e.association_id=:associationId");
            if (!actor.partnerAssociationIds().isEmpty()) {
                params.addValue("partnerIds", actor.partnerAssociationIds());
                appendAuthorizedPartnerRead(sql, alias);
            }
            sql.append(")");
        } else if (actor.enterpriseId() != null) {
            sql.append("(").append(alias).append(".enterprise_id=:enterpriseId");
            if (actor.associationId() != null) {
                sql.append(" OR (e.association_id=:associationId AND ")
                        .append(alias).append(".visibility IN ('MEMBERS','PUBLIC') AND ")
                        .append(alias).append(".status=:publishedStatus)");
            }
            if (!actor.partnerAssociationIds().isEmpty()) {
                params.addValue("partnerIds", actor.partnerAssociationIds());
                appendAuthorizedPartnerRead(sql, alias);
            }
            sql.append(")");
        } else {
            sql.append("FALSE");
        }
        params.addValue("publishedStatus", publishedStatus);
        if (!actor.isSystemAdmin() && !actor.isAssociationStaff()) {
            sql.append(" AND e.status='ACTIVE' AND e.deleted_at IS NULL");
        }
        if (!includeDeleted) {
            sql.append(" AND ").append(alias).append(".deleted_at IS NULL");
        } else if (actor.isSystemAdmin() || actor.isAssociationStaff()) {
            // The actor scope above already limits system and association administrators.
        } else if (actor.isEnterpriseAdmin()) {
            sql.append(" AND (").append(alias).append(".deleted_at IS NULL OR ")
                    .append(alias).append(".enterprise_id=:enterpriseId)");
        } else {
            sql.append(" AND ").append(alias).append(".deleted_at IS NULL");
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (lower(").append(alias).append(".name) LIKE :query")
                    .append(" OR lower(coalesce(").append(alias).append(".description,'')) LIKE :query")
                    .append(" OR lower(e.name) LIKE :query)");
            if ("d".equals(alias)) {
                int start = sql.indexOf("lower(d.name)");
                sql.replace(start, start + "lower(d.name)".length(), "lower(d.title)");
            }
        }
        return sql.toString();
    }

    private static void appendAuthorizedPartnerRead(StringBuilder sql, String alias) {
        String resourceType = "p".equals(alias) ? "upper(p.kind)" : "'DEMAND'";
        sql.append(" OR (e.association_id IN (:partnerIds) AND ")
                .append("e.status='ACTIVE' AND e.deleted_at IS NULL AND ")
                .append(alias).append(".visibility IN ('PARTNERS','PUBLIC') AND ")
                .append(alias).append(".status=:publishedStatus AND ")
                .append(alias).append(".deleted_at IS NULL AND ")
                .append("EXISTS (SELECT 1 FROM association_relationship ar ")
                .append("WHERE ar.status='ACTIVE' AND ar.allow_member_data=TRUE ")
                .append("AND ar.suspended_at IS NULL AND ar.revoked_at IS NULL ")
                .append("AND (ar.expires_at IS NULL OR ar.expires_at>now()) ")
                .append("AND ((ar.source_association_id=e.association_id AND ar.target_association_id=:associationId) ")
                .append("OR (ar.target_association_id=e.association_id AND ar.source_association_id=:associationId))) ")
                .append("AND EXISTS (SELECT 1 FROM association_share_policy sp ")
                .append("WHERE sp.source_association_id=e.association_id ")
                .append("AND sp.target_association_id=:associationId ")
                .append("AND sp.resource_type=").append(resourceType).append(" ")
                .append("AND sp.status='ACTIVE' AND sp.valid_from<=now() ")
                .append("AND (sp.expires_at IS NULL OR sp.expires_at>now())) ")
                .append("AND EXISTS (SELECT 1 FROM enterprise_share_consent esc ")
                .append("WHERE esc.enterprise_id=").append(alias).append(".enterprise_id ")
                .append("AND esc.target_association_id=:associationId ")
                .append("AND esc.resource_type=").append(resourceType).append(" ")
                .append("AND esc.resource_id=").append(alias).append(".id ")
                .append("AND esc.status='ACTIVE' AND esc.revoked_at IS NULL ")
                .append("AND (esc.expires_at IS NULL OR esc.expires_at>now())))");
    }

    private MapSqlParameterSource commonParams(ActorScope actor, String query) {
        return new MapSqlParameterSource()
                .addValue("associationId", actor.associationId())
                .addValue("enterpriseId", actor.enterpriseId())
                .addValue("query", query == null ? null : "%" + query.trim().toLowerCase() + "%");
    }

    private void requireSystemCreateScope(UUID enterpriseId, ActorScope actor) {
        if (!actor.isSystemAdmin()) {
            return;
        }
        EcosystemScopeGuard.requireWriteContext(actor);
        if (actor.enterpriseId() == null || !actor.enterpriseId().equals(enterpriseId)) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "catalog records must be created for the selected enterprise");
        }
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM enterprise
                     WHERE id=:enterpriseId AND association_id=:associationId
                       AND status='ACTIVE' AND deleted_at IS NULL)
                """, new MapSqlParameterSource("enterpriseId", enterpriseId)
                .addValue("associationId", actor.associationId()), Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "selected enterprise is outside the selected association");
        }
    }

    private static MapSqlParameterSource writeParams(
            MapSqlParameterSource params, ActorScope actor) {
        return params.addValue("associationId", actor.associationId())
                .addValue("contextEnterpriseId", actor.enterpriseId());
    }

    private static String systemWriteClause(String alias, ActorScope actor) {
        if (!actor.isSystemAdmin()) {
            return "";
        }
        if (actor.associationId() == null) {
            return " AND FALSE";
        }
        String enterprise = actor.enterpriseId() == null
                ? ""
                : " AND scope_enterprise.id=:contextEnterpriseId";
        return " AND EXISTS (SELECT 1 FROM enterprise scope_enterprise"
                + " WHERE scope_enterprise.id=" + alias + ".enterprise_id"
                + " AND scope_enterprise.association_id=:associationId"
                + enterprise + ")";
    }

    private MapSqlParameterSource offeringParams(OfferingUpsertRequest request) {
        return new MapSqlParameterSource()
                .addValue("name", request.name().trim())
                .addValue("kind", request.kind())
                .addValue("description", clean(request.description()))
                .addValue("scenarios", json(list(request.scenarios())))
                .addValue("qualifications", json(list(request.qualifications())))
                .addValue("visibility", visibility(request.visibility(), "MEMBERS"));
    }

    private MapSqlParameterSource demandParams(DemandUpsertRequest request) {
        return new MapSqlParameterSource()
                .addValue("title", request.title().trim())
                .addValue("description", request.description().trim())
                .addValue("scenarios", json(list(request.scenarios())))
                .addValue("requiredCapabilities", json(list(request.requiredCapabilities())))
                .addValue("visibility", visibility(request.visibility(), "DIRECTED"))
                .addValue("budgetMin", request.budgetMin())
                .addValue("budgetMax", request.budgetMax())
                .addValue("responseDeadline", request.responseDeadline() == null
                        ? null : Timestamp.from(request.responseDeadline()));
    }

    private OfferingView mapOffering(ResultSet rs, int rowNum) throws SQLException {
        return new OfferingView(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("description"),
                readList(rs.getString("scenarios")),
                readList(rs.getString("qualifications")),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getTimestamp("disabled_at") != null,
                instant(rs.getTimestamp("updated_at")));
    }

    private DemandView mapDemand(ResultSet rs, int rowNum) throws SQLException {
        return new DemandView(
                rs.getObject("id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"),
                rs.getString("title"),
                rs.getString("description"),
                readList(rs.getString("scenarios")),
                readList(rs.getString("required_capabilities")),
                rs.getString("visibility"),
                rs.getBigDecimal("budget_min"),
                rs.getBigDecimal("budget_max"),
                instant(rs.getTimestamp("response_deadline")),
                rs.getString("status"),
                rs.getString("close_reason"),
                rs.getLong("version"),
                rs.getTimestamp("disabled_at") != null,
                instant(rs.getTimestamp("updated_at")));
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid JSON array in ecosystem catalog", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize ecosystem catalog value", exception);
        }
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String visibility(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
