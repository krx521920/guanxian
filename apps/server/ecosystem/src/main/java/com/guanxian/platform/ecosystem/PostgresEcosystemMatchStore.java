package com.guanxian.platform.ecosystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "guanxian.business.repository",
        havingValue = "postgres",
        matchIfMissing = true)
class PostgresEcosystemMatchStore implements EcosystemMatchStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String SELECT = """
            SELECT m.id, m.demand_id, d.enterprise_id AS demand_enterprise_id,
                   m.candidate_enterprise_id,
                   coalesce(m.demand_company_snapshot, de.name) AS demand_company,
                   coalesce(m.demand_title_snapshot, d.title) AS demand_title,
                   m.scene_snapshot, coalesce(m.supplier_company_snapshot, ce.name) AS supplier_company,
                   m.solution, m.score, m.reasons::text AS reasons, m.state,
                   m.recommended_at, m.demand_confirmed_at, m.candidate_confirmed_at,
                   m.closed_reason, m.version, m.updated_at
              FROM ecosystem_match m
              JOIN cooperation_demand d ON d.id=m.demand_id
              JOIN enterprise de ON de.id=d.enterprise_id
              JOIN enterprise ce ON ce.id=m.candidate_enterprise_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<PersistedMatchView> mapper = this::map;

    PostgresEcosystemMatchStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PersistedMatchView> upsert(
            DemandView demand, List<MatchCandidateDraft> candidates, ActorScope actor) {
        requireUpsertScope(demand, actor);
        List<UUID> changedCandidateIds = new ArrayList<>();
        for (MatchCandidateDraft candidate : candidates) {
            if (demand.enterpriseId().equals(candidate.candidateEnterpriseId())) {
                throw new PreconditionFailedException("a demand enterprise cannot be matched with itself");
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("demandId", demand.id())
                    .addValue("candidateId", candidate.candidateEnterpriseId())
                    .addValue("demandCompany", demand.enterpriseName())
                    .addValue("demandTitle", demand.title())
                    .addValue("scene", demand.scenarios().isEmpty() ? null : demand.scenarios().getFirst())
                    .addValue("supplierCompany", candidate.supplierCompany())
                    .addValue("solution", candidate.solution())
                    .addValue("score", candidate.score())
                    .addValue("reasons", json(candidate.reasons()));
            int changed = jdbc.update("""
                    INSERT INTO ecosystem_match (
                        demand_id, candidate_enterprise_id, score, explanation, review_status,
                        demand_company_snapshot, demand_title_snapshot, scene_snapshot,
                        supplier_company_snapshot, solution, reasons, state)
                    VALUES (
                        :demandId, :candidateId, :score, '{}'::jsonb, 'PENDING',
                        :demandCompany, :demandTitle, :scene, :supplierCompany,
                        :solution, CAST(:reasons AS jsonb), 'PENDING_CONFIRMATION')
                    ON CONFLICT (demand_id, candidate_enterprise_id)
                      WHERE deleted_at IS NULL
                    DO UPDATE SET
                        score=excluded.score,
                        demand_company_snapshot=excluded.demand_company_snapshot,
                        demand_title_snapshot=excluded.demand_title_snapshot,
                        scene_snapshot=excluded.scene_snapshot,
                        supplier_company_snapshot=excluded.supplier_company_snapshot,
                        solution=excluded.solution,
                        reasons=excluded.reasons,
                        version=ecosystem_match.version+1,
                        updated_at=now()
                    WHERE ecosystem_match.state='PENDING_CONFIRMATION'
                    """, params);
            if (changed == 1) {
                changedCandidateIds.add(candidate.candidateEnterpriseId());
            }
        }
        if (changedCandidateIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> changed = new HashSet<>(changedCandidateIds);
        return list(demand.id(), actor).stream()
                .filter(value -> changed.contains(value.candidateEnterpriseId()))
                .toList();
    }

    @Override
    public List<PersistedMatchView> list(UUID demandId, ActorScope actor) {
        MapSqlParameterSource params = scopeParams(actor).addValue("demandId", demandId);
        return jdbc.query(SELECT + scope(actor)
                        + " AND m.demand_id=:demandId AND m.deleted_at IS NULL"
                        + " ORDER BY m.score DESC, supplier_company, m.id",
                params, mapper);
    }

    @Override
    public List<PersistedMatchView> list(ActorScope actor) {
        return list(actor, null, 0, Integer.MAX_VALUE);
    }

    @Override
    public List<PersistedMatchView> list(
            ActorScope actor, String state, long offset, int limit) {
        MapSqlParameterSource params = scopeParams(actor)
                .addValue("state", state, Types.VARCHAR)
                .addValue("offset", offset)
                .addValue("limit", limit);
        return jdbc.query(SELECT + scope(actor)
                        + " AND m.deleted_at IS NULL"
                        + " AND (:state IS NULL OR m.state=:state)"
                        + " ORDER BY m.score DESC NULLS LAST, m.id"
                        + " OFFSET :offset LIMIT :limit",
                params, mapper);
    }

    @Override
    public long count(ActorScope actor, String state) {
        Long value = jdbc.queryForObject("""
                SELECT count(*)
                  FROM ecosystem_match m
                  JOIN cooperation_demand d ON d.id=m.demand_id
                  JOIN enterprise de ON de.id=d.enterprise_id
                  JOIN enterprise ce ON ce.id=m.candidate_enterprise_id
                """ + scope(actor)
                        + " AND m.deleted_at IS NULL"
                        + " AND (:state IS NULL OR m.state=:state)",
                scopeParams(actor).addValue("state", state, Types.VARCHAR), Long.class);
        return value == null ? 0 : value;
    }

    @Override
    public Optional<PersistedMatchView> find(UUID id, ActorScope actor) {
        MapSqlParameterSource params = scopeParams(actor).addValue("id", id);
        return jdbc.query(SELECT + scope(actor)
                        + " AND m.id=:id AND m.deleted_at IS NULL",
                params, mapper).stream().findFirst();
    }

    @Override
    public Optional<PersistedMatchView> recommend(
            UUID id, long expectedVersion, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE ecosystem_match
                   SET state=CASE
                           WHEN state='PENDING_CONFIRMATION' THEN 'RECOMMENDED'
                           ELSE state END,
                       review_status='APPROVED',
                       recommended_by_subject=:subject,
                       recommended_at=now(),
                       version=version+1,
                       updated_at=now()
                 WHERE id=:id
                   AND version=:expectedVersion
                   AND state='PENDING_CONFIRMATION'
                   AND recommended_at IS NULL
                   AND deleted_at IS NULL
                """ + writeScope("ecosystem_match", actor, false)
                + operationalWriteScope("ecosystem_match"), scopeParams(actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : find(id, actor);
    }

    @Override
    public Optional<PersistedMatchView> confirm(
            UUID id, long expectedVersion, UUID enterpriseId, ActorScope actor) {
        int updated = jdbc.update("""
                UPDATE ecosystem_match m
                   SET demand_confirmed_by_subject=CASE
                           WHEN d.enterprise_id=:enterpriseId THEN :subject
                           ELSE demand_confirmed_by_subject END,
                       demand_confirmed_at=CASE
                           WHEN d.enterprise_id=:enterpriseId THEN now()
                           ELSE demand_confirmed_at END,
                       candidate_confirmed_by_subject=CASE
                           WHEN m.candidate_enterprise_id=:enterpriseId THEN :subject
                           ELSE candidate_confirmed_by_subject END,
                       candidate_confirmed_at=CASE
                           WHEN m.candidate_enterprise_id=:enterpriseId THEN now()
                           ELSE candidate_confirmed_at END,
                       state=CASE
                           WHEN d.enterprise_id=:enterpriseId AND m.candidate_confirmed_at IS NOT NULL
                               THEN 'CONFIRMED'
                           WHEN m.candidate_enterprise_id=:enterpriseId AND m.demand_confirmed_at IS NOT NULL
                               THEN 'CONFIRMED'
                           ELSE 'PARTIALLY_CONFIRMED' END,
                       version=m.version+1,
                       updated_at=now()
                  FROM cooperation_demand d
                 WHERE m.demand_id=d.id
                   AND m.id=:id
                   AND m.version=:expectedVersion
                   AND m.state IN ('RECOMMENDED', 'PARTIALLY_CONFIRMED')
                   AND m.recommended_at IS NOT NULL
                   AND (d.enterprise_id=:enterpriseId OR m.candidate_enterprise_id=:enterpriseId)
                   AND ((d.enterprise_id=:enterpriseId AND m.demand_confirmed_at IS NULL)
                     OR (m.candidate_enterprise_id=:enterpriseId AND m.candidate_confirmed_at IS NULL))
                   AND m.deleted_at IS NULL
                """ + writeScope("m", actor, true)
                + operationalWriteScope("m"), scopeParams(actor)
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("enterpriseId", enterpriseId)
                .addValue("subject", actor.subject()));
        return updated == 0 ? Optional.empty() : find(id, actor);
    }

    @Override
    public Optional<PersistedMatchView> transition(
            UUID id, long expectedVersion, String targetState, String closeReason, ActorScope actor) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expectedVersion", expectedVersion)
                .addValue("targetState", targetState)
                .addValue("closeReason", closeReason);
        int updated = jdbc.update("""
                UPDATE ecosystem_match
                   SET state=:targetState,
                       review_status=CASE
                           WHEN :targetState='CLOSED' THEN 'CLOSED'
                           ELSE review_status END,
                       closed_reason=CASE WHEN :targetState='CLOSED' THEN :closeReason ELSE NULL END,
                       version=version+1,
                       updated_at=now()
                 WHERE id=:id AND version=:expectedVersion AND deleted_at IS NULL
                """ + writeScope("ecosystem_match", actor, false)
                + operationalWriteScope("ecosystem_match"), addScopeParams(params, actor));
        return updated == 0 ? Optional.empty() : find(id, actor);
    }

    private String scope(ActorScope actor) {
        String actorScope;
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                actorScope = actor.enterpriseId() == null ? "TRUE" : "FALSE";
            } else if (actor.enterpriseId() != null) {
                actorScope = "((de.association_id=:associationId"
                        + " AND de.status='ACTIVE' AND de.deleted_at IS NULL"
                        + " AND d.enterprise_id=:enterpriseId)"
                        + " OR (m.state<>'PENDING_CONFIRMATION'"
                        + " AND ce.association_id=:associationId"
                        + " AND ce.status='ACTIVE' AND ce.deleted_at IS NULL"
                        + " AND m.candidate_enterprise_id=:enterpriseId))";
            } else {
                actorScope = "((de.association_id=:associationId"
                        + " AND de.status='ACTIVE' AND de.deleted_at IS NULL)"
                        + " OR (m.state<>'PENDING_CONFIRMATION'"
                        + " AND ce.association_id=:associationId"
                        + " AND ce.status='ACTIVE' AND ce.deleted_at IS NULL))";
            }
        } else if (actor.isAssociationStaff()) {
            actorScope = actor.partnerAssociationIds().isEmpty()
                    ? "(de.association_id=:associationId"
                    + " AND de.status='ACTIVE' AND de.deleted_at IS NULL)"
                    : "((de.association_id=:associationId"
                    + " AND de.status='ACTIVE' AND de.deleted_at IS NULL)"
                    + " OR (m.state<>'PENDING_CONFIRMATION' AND "
                    + authorizedPartnerMatchRead() + "))";
        } else if (actor.enterpriseId() != null) {
            actorScope = actor.partnerAssociationIds().isEmpty()
                    ? "(d.enterprise_id=:enterpriseId"
                    + " OR (m.state<>'PENDING_CONFIRMATION'"
                    + " AND m.candidate_enterprise_id=:enterpriseId))"
                    : "(d.enterprise_id=:enterpriseId"
                    + " OR (m.state<>'PENDING_CONFIRMATION'"
                    + " AND (m.candidate_enterprise_id=:enterpriseId OR "
                    + authorizedPartnerMatchRead() + ")))";
        } else {
            actorScope = "FALSE";
        }
        String lifecycleScope = actor.isSystemAdmin() || actor.isAssociationStaff()
                ? ""
                : " AND de.status='ACTIVE' AND de.deleted_at IS NULL"
                + " AND ce.status='ACTIVE' AND ce.deleted_at IS NULL"
                + " AND d.deleted_at IS NULL";
        return " WHERE " + actorScope + lifecycleScope;
    }

    private static String authorizedPartnerMatchRead() {
        return "(" + authorizedPartnerOwner("de", "d.enterprise_id")
                + " AND (d.enterprise_id=m.candidate_enterprise_id"
                + " OR ce.association_id=:associationId OR "
                + authorizedPartnerOwner("ce", "m.candidate_enterprise_id") + "))";
    }

    private static String authorizedPartnerOwner(String enterpriseAlias, String enterpriseIdExpression) {
        return "(" + enterpriseAlias + ".association_id<>:associationId"
                + " AND " + enterpriseAlias + ".association_id IN (:partnerAssociationIds)"
                + " AND " + enterpriseAlias + ".status='ACTIVE'"
                + " AND " + enterpriseAlias + ".deleted_at IS NULL"
                + " AND d.deleted_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM association_relationship ar"
                + " WHERE ar.status='ACTIVE' AND ar.allow_member_data=TRUE"
                + " AND ar.suspended_at IS NULL AND ar.revoked_at IS NULL"
                + " AND (ar.expires_at IS NULL OR ar.expires_at>now())"
                + " AND ((ar.source_association_id=" + enterpriseAlias + ".association_id"
                + " AND ar.target_association_id=:associationId)"
                + " OR (ar.target_association_id=" + enterpriseAlias + ".association_id"
                + " AND ar.source_association_id=:associationId)))"
                + " AND EXISTS (SELECT 1 FROM association_share_policy sp"
                + " WHERE sp.source_association_id=" + enterpriseAlias + ".association_id"
                + " AND sp.target_association_id=:associationId"
                + " AND sp.resource_type='MATCH' AND sp.status='ACTIVE'"
                + " AND sp.valid_from<=now() AND (sp.expires_at IS NULL OR sp.expires_at>now())"
                + " AND jsonb_typeof(sp.visible_fields)='array'"
                + " AND sp.visible_fields<>'[]'::jsonb"
                + " AND sp.visible_fields <@ CAST('[\"demandCompany\",\"demandTitle\",\"scene\","
                + "\"supplierCompany\",\"solution\",\"score\",\"reasons\",\"state\",\"outcomes\"]' AS jsonb))"
                + " AND EXISTS (SELECT 1 FROM enterprise_share_consent esc"
                + " WHERE esc.enterprise_id=" + enterpriseIdExpression
                + " AND esc.target_association_id=:associationId"
                + " AND esc.resource_type='MATCH' AND esc.resource_id=m.id"
                + " AND esc.status='ACTIVE' AND esc.revoked_at IS NULL"
                + " AND (esc.expires_at IS NULL OR esc.expires_at>now())))";
    }

    private static MapSqlParameterSource scopeParams(ActorScope actor) {
        return new MapSqlParameterSource()
                .addValue("associationId", actor.associationId())
                .addValue("enterpriseId", actor.enterpriseId())
                .addValue("partnerAssociationIds", actor.partnerAssociationIds())
                .addValue("contextEnterpriseId", actor.enterpriseId());
    }

    private static MapSqlParameterSource addScopeParams(
            MapSqlParameterSource params, ActorScope actor) {
        return params.addValue("associationId", actor.associationId())
                .addValue("contextEnterpriseId", actor.enterpriseId());
    }

    private static String writeScope(
            String matchAlias, ActorScope actor, boolean requireSelectedEnterprise) {
        String scope;
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                return " AND FALSE";
            }
            scope = " AND EXISTS (SELECT 1 FROM cooperation_demand scope_demand"
                    + " JOIN enterprise scope_demand_enterprise"
                    + " ON scope_demand_enterprise.id=scope_demand.enterprise_id"
                    + " JOIN enterprise scope_candidate_enterprise"
                    + " ON scope_candidate_enterprise.id=" + matchAlias + ".candidate_enterprise_id"
                    + " WHERE scope_demand.id=" + matchAlias + ".demand_id"
                    + (actor.enterpriseId() == null
                    ? " AND (scope_demand_enterprise.association_id=:associationId"
                    + " OR scope_candidate_enterprise.association_id=:associationId)"
                    : " AND ((scope_demand_enterprise.association_id=:associationId"
                    + " AND scope_demand.enterprise_id=:contextEnterpriseId)"
                    + " OR (scope_candidate_enterprise.association_id=:associationId"
                    + " AND " + matchAlias + ".candidate_enterprise_id=:contextEnterpriseId))")
                    + ")";
        } else if (actor.isAssociationStaff()) {
            if (actor.associationId() == null) {
                return " AND FALSE";
            }
            scope = " AND EXISTS (SELECT 1 FROM cooperation_demand scope_demand"
                    + " JOIN enterprise scope_enterprise"
                    + " ON scope_enterprise.id=scope_demand.enterprise_id"
                    + " WHERE scope_demand.id=" + matchAlias + ".demand_id"
                    + " AND scope_enterprise.association_id=:associationId)";
        } else if (actor.enterpriseId() != null) {
            scope = " AND EXISTS (SELECT 1 FROM cooperation_demand scope_demand"
                    + " WHERE scope_demand.id=" + matchAlias + ".demand_id"
                    + " AND (scope_demand.enterprise_id=:contextEnterpriseId"
                    + " OR " + matchAlias + ".candidate_enterprise_id=:contextEnterpriseId))";
        } else {
            return " AND FALSE";
        }
        if (requireSelectedEnterprise) {
            return actor.enterpriseId() == null
                    ? " AND FALSE"
                    : scope + " AND :enterpriseId=:contextEnterpriseId";
        }
        return scope;
    }

    private static String operationalWriteScope(String matchAlias) {
        return " AND EXISTS (SELECT 1 FROM cooperation_demand operational_demand"
                + " JOIN enterprise operational_demand_enterprise"
                + " ON operational_demand_enterprise.id=operational_demand.enterprise_id"
                + " JOIN enterprise operational_candidate_enterprise"
                + " ON operational_candidate_enterprise.id=" + matchAlias + ".candidate_enterprise_id"
                + " WHERE operational_demand.id=" + matchAlias + ".demand_id"
                + " AND operational_demand.deleted_at IS NULL"
                + " AND operational_demand_enterprise.status='ACTIVE'"
                + " AND operational_demand_enterprise.deleted_at IS NULL"
                + " AND operational_candidate_enterprise.status='ACTIVE'"
                + " AND operational_candidate_enterprise.deleted_at IS NULL)";
    }

    private void requireUpsertScope(DemandView demand, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        if (!actor.isSystemAdmin() && !actor.isAssociationStaff()
                && (!actor.isEnterpriseAdmin() || actor.enterpriseId() == null
                || !actor.enterpriseId().equals(demand.enterpriseId()))) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "matches must be generated for the selected enterprise's demand");
        }
        if (actor.isSystemAdmin() && actor.enterpriseId() != null
                && !actor.enterpriseId().equals(demand.enterpriseId())) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "matches must be generated for the selected enterprise's demand");
        }
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM cooperation_demand d
                      JOIN enterprise e ON e.id=d.enterprise_id
                      JOIN association a ON a.id=e.association_id
                     WHERE d.id=:demandId
                       AND d.enterprise_id=:enterpriseId
                       AND d.deleted_at IS NULL
                       AND e.association_id=:associationId
                       AND e.status='ACTIVE'
                       AND e.deleted_at IS NULL
                       AND a.status='ACTIVE')
                """, new MapSqlParameterSource()
                .addValue("demandId", demand.id())
                .addValue("enterpriseId", demand.enterpriseId())
                .addValue("associationId", actor.associationId()), Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "demand is outside the selected association context");
        }
    }

    private PersistedMatchView map(ResultSet rs, int rowNum) throws SQLException {
        return new PersistedMatchView(
                rs.getObject("id", UUID.class),
                rs.getObject("demand_id", UUID.class),
                rs.getObject("demand_enterprise_id", UUID.class),
                rs.getObject("candidate_enterprise_id", UUID.class),
                rs.getString("demand_company"),
                rs.getString("demand_title"),
                rs.getString("scene_snapshot"),
                rs.getString("supplier_company"),
                rs.getString("solution"),
                rs.getBigDecimal("score").intValue(),
                readList(rs.getString("reasons")),
                rs.getString("state"),
                instant(rs.getTimestamp("recommended_at")),
                instant(rs.getTimestamp("demand_confirmed_at")),
                instant(rs.getTimestamp("candidate_confirmed_at")),
                rs.getString("closed_reason"),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant(),
                java.util.Set.of());
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored match reasons are invalid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize match reasons", exception);
        }
    }
}
