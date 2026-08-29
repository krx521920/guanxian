package com.guanxian.platform.ecosystem;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        for (MatchCandidateDraft candidate : candidates) {
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
            jdbc.update("""
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
                    WHERE ecosystem_match.state IN (
                        'PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED')
                    """, params);
        }
        return list(demand.id(), actor);
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
        return jdbc.query(SELECT + scope(actor)
                        + " AND m.deleted_at IS NULL"
                        + " ORDER BY m.updated_at DESC, m.score DESC, m.id",
                scopeParams(actor), mapper);
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
                   AND state IN ('PENDING_CONFIRMATION', 'PARTIALLY_CONFIRMED')
                   AND recommended_at IS NULL
                   AND deleted_at IS NULL
                """, new MapSqlParameterSource()
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
                   AND m.state IN ('PENDING_CONFIRMATION', 'RECOMMENDED', 'PARTIALLY_CONFIRMED')
                   AND (d.enterprise_id=:enterpriseId OR m.candidate_enterprise_id=:enterpriseId)
                   AND ((d.enterprise_id=:enterpriseId AND m.demand_confirmed_at IS NULL)
                     OR (m.candidate_enterprise_id=:enterpriseId AND m.candidate_confirmed_at IS NULL))
                   AND m.deleted_at IS NULL
                """, new MapSqlParameterSource()
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
                """, params);
        return updated == 0 ? Optional.empty() : find(id, actor);
    }

    private String scope(ActorScope actor) {
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
                rs.getTimestamp("updated_at").toInstant());
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
