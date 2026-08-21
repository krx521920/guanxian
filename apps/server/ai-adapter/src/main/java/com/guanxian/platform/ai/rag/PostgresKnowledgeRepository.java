package com.guanxian.platform.ai.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.rag.DocumentTextChunker.TextChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnBean(NamedParameterJdbcTemplate.class)
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class PostgresKnowledgeRepository implements KnowledgeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresKnowledgeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public IngestionResult ingest(IngestCommand command) {
        UUID documentId = command.documentId();
        int version;
        if (documentId == null) {
            documentId = jdbc.queryForObject("""
                    INSERT INTO knowledge_document (
                      association_id, title, document_type, source_type, source_url, visibility, status,
                      current_version, content_hash, created_by_subject
                    ) VALUES (
                      :associationId, :title, :documentType, :sourceType, :sourceUrl, :visibility, :status,
                      1, :contentHash, :actorSubject
                    ) RETURNING id
                    """, commonParams(command), UUID.class);
            version = 1;
        } else {
            MapSqlParameterSource lockParams = new MapSqlParameterSource("documentId", documentId)
                    .addValue("associationId", command.associationId());
            Integer current = jdbc.queryForObject("""
                    SELECT current_version FROM knowledge_document
                    WHERE id = :documentId
                      AND deleted_at IS NULL
                      AND association_id IS NOT DISTINCT FROM CAST(:associationId AS UUID)
                    FOR UPDATE
                    """, lockParams, Integer.class);
            if (current == null) throw new IllegalArgumentException("knowledge document does not exist in this association");
            version = current + 1;
            MapSqlParameterSource update = commonParams(command).addValue("documentId", documentId).addValue("version", version);
            int changed = jdbc.update("""
                    UPDATE knowledge_document
                    SET title = :title, document_type = :documentType, source_type = :sourceType,
                        source_url = :sourceUrl, visibility = :visibility, status = :status,
                        current_version = :version, content_hash = :contentHash, updated_at = now()
                    WHERE id = :documentId
                    """, update);
            if (changed != 1) throw new IllegalStateException("knowledge document version update failed");
        }

        MapSqlParameterSource versionParams = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("version", version)
                .addValue("actorSubject", command.actorSubject());
        UUID versionId = jdbc.queryForObject("""
                INSERT INTO knowledge_document_version (
                  document_id, version, parser_name, parser_version, status, created_by_subject
                ) VALUES (:documentId, :version, 'guanxian-text-chunker', '1', 'READY', :actorSubject)
                RETURNING id
                """, versionParams, UUID.class);

        SqlParameterSource[] batches = command.chunks().stream()
                .map(chunk -> chunkParams(versionId, chunk))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO knowledge_chunk (
                  document_version_id, chunk_index, content, content_hash, token_count, metadata
                ) VALUES (
                  :versionId, :chunkIndex, :content, :contentHash, :tokenCount, CAST(:metadata AS JSONB)
                )
                """, batches);
        return new IngestionResult(documentId, versionId, version, command.chunks().size(), command.contentHash());
    }

    @Override
    public List<RetrievedChunk> retrieve(UUID associationId, String query, int limit) {
        List<String> terms = MemoryKnowledgeRepository.queryTerms(query).stream()
                .filter(term -> term.length() >= 2)
                .limit(12)
                .toList();
        if (terms.isEmpty()) return List.of();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("associationId", associationId)
                .addValue("limit", Math.min(Math.max(1, limit), 12));
        List<String> matches = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            String name = "term" + index;
            params.addValue(name, "%" + escapeLike(terms.get(index)) + "%");
            matches.add("LOWER(kc.content) LIKE :" + name + " ESCAPE '\\'");
            scores.add("CASE WHEN LOWER(kc.content) LIKE :" + name + " ESCAPE '\\' THEN " + Math.max(1, 12 - index) + " ELSE 0 END");
        }
        String sql = """
                SELECT kc.id AS chunk_id, d.id AS document_id, d.title, dv.version, kc.chunk_index,
                       d.source_url, kc.content, (%s) AS relevance
                FROM knowledge_chunk kc
                JOIN knowledge_document_version dv ON dv.id = kc.document_version_id
                JOIN knowledge_document d ON d.id = dv.document_id
                WHERE d.deleted_at IS NULL
                  AND d.status = 'PUBLISHED'
                  AND dv.status = 'READY'
                  AND dv.version = d.current_version
                  AND (d.visibility = 'PUBLIC'
                       OR (CAST(:associationId AS UUID) IS NOT NULL AND d.association_id = CAST(:associationId AS UUID)))
                  AND (%s)
                ORDER BY relevance DESC, d.updated_at DESC, kc.chunk_index
                LIMIT :limit
                """.formatted(String.join(" + ", scores), String.join(" OR ", matches));

        return jdbc.query(sql, params, (resultSet, rowNum) -> new RetrievedChunk(
                resultSet.getObject("chunk_id", UUID.class),
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("title"),
                resultSet.getInt("version"),
                resultSet.getInt("chunk_index"),
                resultSet.getString("source_url"),
                resultSet.getString("content"),
                resultSet.getDouble("relevance")
        ));
    }

    @Override
    @Transactional
    public UUID saveRetrieval(TraceDraft trace, List<CitationDraft> citations) {
        List<UUID> chunkIds = citations.stream().map(CitationDraft::chunkId).toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("associationId", trace.associationId())
                .addValue("actorSubject", required(trace.actorSubject(), "actor subject"))
                .addValue("question", required(trace.question(), "question"))
                .addValue("queryHash", required(trace.queryHash(), "query hash"))
                .addValue("provider", trace.provider())
                .addValue("model", trace.model())
                .addValue("chunkIds", json(chunkIds))
                .addValue("answerStatus", required(trace.answerStatus(), "answer status"))
                .addValue("inputTokens", trace.inputTokens())
                .addValue("outputTokens", trace.outputTokens())
                .addValue("estimatedCost", trace.estimatedCost())
                .addValue("latencyMs", safeInt(trace.latencyMs()))
                .addValue("requestId", trace.requestId());
        UUID traceId = jdbc.queryForObject("""
                INSERT INTO retrieval_trace (
                  association_id, actor_subject, question, query_hash, provider, model, retrieved_chunk_ids,
                  answer_status, input_tokens, output_tokens, estimated_cost, latency_ms, request_id
                ) VALUES (
                  :associationId, :actorSubject, :question, :queryHash, :provider, :model, CAST(:chunkIds AS JSONB),
                  :answerStatus, :inputTokens, :outputTokens, :estimatedCost, :latencyMs, :requestId
                ) RETURNING id
                """, params, UUID.class);

        for (int index = 0; index < citations.size(); index++) {
            CitationDraft citation = citations.get(index);
            jdbc.update("""
                    INSERT INTO qa_citation (retrieval_trace_id, chunk_id, citation_order, quote_text, score)
                    VALUES (:traceId, :chunkId, :citationOrder, :quoteText, :score)
                    """, new MapSqlParameterSource()
                    .addValue("traceId", traceId)
                    .addValue("chunkId", citation.chunkId())
                    .addValue("citationOrder", index + 1)
                    .addValue("quoteText", citation.quote())
                    .addValue("score", BigDecimal.valueOf(citation.score())));
        }
        return traceId;
    }

    @Override
    public UUID saveModelExecution(ModelExecutionDraft execution) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("associationId", execution.associationId())
                .addValue("actorSubject", execution.actorSubject())
                .addValue("purpose", required(execution.purpose(), "purpose"))
                .addValue("provider", required(execution.provider(), "provider"))
                .addValue("model", required(execution.model(), "model"))
                .addValue("status", required(execution.status(), "status"))
                .addValue("promptHash", execution.promptHash())
                .addValue("inputTokens", execution.inputTokens())
                .addValue("outputTokens", execution.outputTokens())
                .addValue("estimatedCost", execution.estimatedCost())
                .addValue("latencyMs", safeInt(execution.latencyMs()))
                .addValue("errorCode", execution.errorCode())
                .addValue("requestId", execution.requestId());
        return jdbc.queryForObject("""
                INSERT INTO model_execution (
                  association_id, actor_subject, purpose, provider, model, status, prompt_hash,
                  input_tokens, output_tokens, estimated_cost, latency_ms, error_code, request_id
                ) VALUES (
                  :associationId, :actorSubject, :purpose, :provider, :model, :status, :promptHash,
                  :inputTokens, :outputTokens, :estimatedCost, :latencyMs, :errorCode, :requestId
                ) RETURNING id
                """, params, UUID.class);
    }

    private MapSqlParameterSource commonParams(IngestCommand command) {
        return new MapSqlParameterSource()
                .addValue("associationId", command.associationId())
                .addValue("title", command.title().trim())
                .addValue("documentType", command.documentType().trim().toUpperCase())
                .addValue("sourceType", command.sourceType().trim().toUpperCase())
                .addValue("sourceUrl", command.sourceUrl())
                .addValue("visibility", command.visibility())
                .addValue("status", command.status())
                .addValue("contentHash", command.contentHash())
                .addValue("actorSubject", command.actorSubject());
    }

    private MapSqlParameterSource chunkParams(UUID versionId, TextChunk chunk) {
        return new MapSqlParameterSource()
                .addValue("versionId", versionId)
                .addValue("chunkIndex", chunk.index())
                .addValue("content", chunk.content())
                .addValue("contentHash", chunk.contentHash())
                .addValue("tokenCount", chunk.tokenCount())
                .addValue("metadata", "{}");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("knowledge trace serialization failed", exception);
        }
    }

    private String escapeLike(String value) {
        return value.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private int safeInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }
}
