package com.guanxian.platform.ai.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class PostgresRagEvaluationStore implements RagEvaluationStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresRagEvaluationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public EvaluationRun save(EvaluationDraft draft) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO rag_evaluation_run (
                    association_id, dataset_name, dataset_hash, total_cases, evidence_recall,
                    citation_precision, refusal_accuracy, estimated_cost, passed, thresholds,
                    case_results, executed_by_subject)
                VALUES (:associationId, :datasetName, :datasetHash, :totalCases, :evidenceRecall,
                    :citationPrecision, :refusalAccuracy, :estimatedCost, :passed,
                    CAST(:thresholds AS JSONB), CAST(:caseResults AS JSONB), :actorSubject)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("associationId", draft.associationId()).addValue("datasetName", draft.datasetName())
                .addValue("datasetHash", draft.datasetHash()).addValue("totalCases", draft.totalCases())
                .addValue("evidenceRecall", draft.evidenceRecall()).addValue("citationPrecision", draft.citationPrecision())
                .addValue("refusalAccuracy", draft.refusalAccuracy()).addValue("estimatedCost", draft.estimatedCost())
                .addValue("passed", draft.passed()).addValue("thresholds", json(draft.thresholds()))
                .addValue("caseResults", json(draft.caseResults())).addValue("actorSubject", draft.executedBySubject()),
                UUID.class);
        return find(id).orElseThrow();
    }

    @Override
    public Optional<EvaluationRun> latest(UUID associationId) {
        return jdbc.query(select() + " WHERE association_id = :associationId ORDER BY created_at DESC LIMIT 1",
                new MapSqlParameterSource("associationId", associationId), (rs, row) -> map(rs)).stream().findFirst();
    }

    private Optional<EvaluationRun> find(UUID id) {
        return jdbc.query(select() + " WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, row) -> map(rs)).stream().findFirst();
    }

    private String select() {
        return """
                SELECT id, association_id, dataset_name, dataset_hash, total_cases, evidence_recall,
                       citation_precision, refusal_accuracy, estimated_cost, passed, thresholds,
                       case_results, executed_by_subject, created_at
                FROM rag_evaluation_run
                """;
    }

    private EvaluationRun map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new EvaluationRun(
                    rs.getObject("id", UUID.class), rs.getObject("association_id", UUID.class),
                    rs.getString("dataset_name"), rs.getString("dataset_hash"), rs.getInt("total_cases"),
                    rs.getBigDecimal("evidence_recall"), rs.getBigDecimal("citation_precision"),
                    rs.getBigDecimal("refusal_accuracy"), rs.getBigDecimal("estimated_cost"),
                    rs.getBoolean("passed"), objectMapper.readValue(rs.getString("thresholds"), MAP),
                    objectMapper.readValue(rs.getString("case_results"), LIST),
                    rs.getString("executed_by_subject"), rs.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored RAG evaluation JSON is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RAG evaluation result could not be serialized", exception);
        }
    }
}
