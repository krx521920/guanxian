package com.guanxian.platform.ai.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.rag.DocumentTextChunker.TextChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@Repository
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
                      association_id, title, document_type, source_type, source_url, source_file_id, visibility, status,
                      current_version, content_hash, created_by_subject
                    ) VALUES (
                      :associationId, :title, :documentType, :sourceType, :sourceUrl, :sourceFileId, :visibility, :status,
                      1, :contentHash, :actorSubject
                    ) RETURNING id
                    """, commonParams(command), UUID.class);
            version = 1;
        } else {
            MapSqlParameterSource lockParams = new MapSqlParameterSource("documentId", documentId)
                    .addValue("associationId", command.associationId());
            Integer current;
            try {
                current = jdbc.queryForObject("""
                        SELECT current_version FROM knowledge_document
                        WHERE id = :documentId
                          AND deleted_at IS NULL
                          AND association_id IS NOT DISTINCT FROM CAST(:associationId AS UUID)
                        FOR UPDATE
                        """, lockParams, Integer.class);
            } catch (EmptyResultDataAccessException exception) {
                throw new IllegalArgumentException(
                        "knowledge document does not exist in this association", exception);
            }
            if (current == null) throw new IllegalArgumentException("knowledge document does not exist in this association");
            version = current + 1;
            MapSqlParameterSource update = commonParams(command).addValue("documentId", documentId).addValue("version", version);
            int changed = jdbc.update("""
                    UPDATE knowledge_document
                    SET title = :title, document_type = :documentType, source_type = :sourceType,
                        source_url = :sourceUrl, source_file_id = :sourceFileId,
                        visibility = :visibility, status = :status,
                        current_version = :version, content_hash = :contentHash, updated_at = now()
                    WHERE id = :documentId
                    """, update);
            if (changed != 1) throw new IllegalStateException("knowledge document version update failed");
        }

        MapSqlParameterSource versionParams = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("version", version)
                .addValue("sourceFileId", command.sourceFileId())
                .addValue("parserName", command.parserName() == null ? "guanxian-text-chunker" : command.parserName())
                .addValue("parserVersion", command.parserVersion() == null ? "1" : command.parserVersion())
                .addValue("pageCount", command.pageCount())
                .addValue("actorSubject", command.actorSubject());
        UUID versionId = jdbc.queryForObject("""
                INSERT INTO knowledge_document_version (
                  document_id, version, source_file_id, parser_name, parser_version, page_count,
                  status, created_by_subject
                ) VALUES (
                  :documentId, :version, :sourceFileId, :parserName, :parserVersion, :pageCount,
                  'READY', :actorSubject)
                RETURNING id
                """, versionParams, UUID.class);

        SqlParameterSource[] batches = IntStream.range(0, command.chunks().size())
                .mapToObj(index -> chunkParams(versionId, command.chunks().get(index),
                        command.embeddings().isEmpty() ? null : command.embeddings().get(index), command))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO knowledge_chunk (
                  document_version_id, chunk_index, content, content_hash, token_count, metadata,
                  embedding_provider, embedding_model, embedding, embedding_status,
                  vector_dimension, embedding_updated_at
                ) VALUES (
                  :versionId, :chunkIndex, :content, :contentHash, :tokenCount, CAST(:metadata AS JSONB),
                  :embeddingProvider, :embeddingModel, CAST(:embedding AS JSONB), :embeddingStatus,
                  :vectorDimension, :embeddingUpdatedAt
                )
                """, batches);
        writeAudit(command, documentId, version);
        return new IngestionResult(documentId, versionId, version, command.chunks().size(), command.contentHash(),
                command.embeddings().isEmpty() ? null : command.embeddingProvider(),
                command.embeddings().isEmpty() ? null : command.embeddingModel(), command.embeddingDimensions());
    }

    private void writeAudit(IngestCommand command, UUID documentId, int version) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "documentTitle", command.title(),
                    "documentType", command.documentType(),
                    "sourceType", command.sourceType(),
                    "visibility", command.visibility(),
                    "status", command.status(),
                    "chunkCount", command.chunks().size(),
                    "newVersion", version));
            jdbc.update("""
                    INSERT INTO audit_log (
                      actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                      action, resource_type, resource_id, resource_version, outcome, details, request_id
                    ) VALUES (
                      (SELECT id FROM user_account WHERE id = :actorUserId),
                      :actorSubject, :actorUsername, :associationId, NULL,
                      :action, 'KNOWLEDGE_DOCUMENT', :resourceId, :resourceVersion, 'SUCCESS',
                      CAST(:details AS JSONB), :requestId
                    )
                    """, new MapSqlParameterSource()
                    .addValue("actorUserId", command.actorUserId())
                    .addValue("actorSubject", command.actorSubject())
                    .addValue("actorUsername", command.actorUsername() == null || command.actorUsername().isBlank()
                            ? command.actorSubject() : command.actorUsername())
                    .addValue("associationId", command.associationId())
                    .addValue("action", version == 1 ? "KNOWLEDGE_CREATE" : "KNOWLEDGE_UPDATE")
                    .addValue("resourceId", documentId.toString())
                    .addValue("resourceVersion", version)
                    .addValue("details", details)
                    .addValue("requestId", MDC.get("requestId") == null ? "internal" : MDC.get("requestId")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("knowledge audit details could not be serialized", exception);
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(RetrievalScope scope, String query, double[] queryEmbedding, int limit) {
        List<String> terms = MemoryKnowledgeRepository.queryTerms(query).stream()
                .filter(term -> term.length() >= 2)
                .limit(12)
                .toList();
        boolean vectorSearch = queryEmbedding != null && queryEmbedding.length >= 8;
        if (terms.isEmpty() && !vectorSearch) return List.of();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("associationId", scope.associationId())
                .addValue("actorSubject", scope.actorSubject())
                .addValue("privileged", scope.privileged())
                .addValue("globalPrivileged", scope.associationId() == null && scope.privileged())
                .addValue("limit", Math.min(Math.max(100, limit * 40), 1000));
        List<String> matches = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            String name = "term" + index;
            params.addValue(name, "%" + escapeLike(terms.get(index)) + "%");
            matches.add("LOWER(kc.content) LIKE :" + name + " ESCAPE '\\'");
            scores.add("CASE WHEN LOWER(kc.content) LIKE :" + name + " ESCAPE '\\' THEN " + Math.max(1, 12 - index) + " ELSE 0 END");
        }
        String lexicalScore = scores.isEmpty() ? "0" : String.join(" + ", scores);
        String candidateMatch = matches.isEmpty() ? "kc.embedding_status = 'READY'"
                : "(" + String.join(" OR ", matches) + ")"
                + (vectorSearch ? " OR kc.embedding_status = 'READY'" : "");
        String sql = """
                SELECT kc.id AS chunk_id, d.id AS document_id, d.title, dv.version, kc.chunk_index,
                       d.source_url, d.source_file_id, fo.original_filename AS source_filename,
                       kc.content, kc.embedding, (%s) AS relevance
                FROM knowledge_chunk kc
                JOIN knowledge_document_version dv ON dv.id = kc.document_version_id
                JOIN knowledge_document d ON d.id = dv.document_id
                LEFT JOIN object_file fo ON fo.id = d.source_file_id
                WHERE d.deleted_at IS NULL
                  AND d.status = 'PUBLISHED'
                  AND dv.status = 'READY'
                  AND dv.version = d.current_version
                  AND (CAST(:globalPrivileged AS BOOLEAN)
                       OR d.visibility = 'PUBLIC'
                       OR (d.visibility = 'ASSOCIATION'
                           AND CAST(:associationId AS UUID) IS NOT NULL
                           AND d.association_id = CAST(:associationId AS UUID))
                       OR (d.visibility = 'PRIVATE'
                           AND CAST(:associationId AS UUID) IS NOT NULL
                           AND d.association_id = CAST(:associationId AS UUID)
                           AND (CAST(:privileged AS BOOLEAN) OR d.created_by_subject = :actorSubject)))
                  AND (%s)
                ORDER BY relevance DESC, d.updated_at DESC, kc.chunk_index
                LIMIT :limit
                """.formatted(lexicalScore, candidateMatch);

        List<CandidateChunk> candidates = jdbc.query(sql, params, (resultSet, rowNum) -> new CandidateChunk(
                resultSet.getObject("chunk_id", UUID.class), resultSet.getObject("document_id", UUID.class),
                resultSet.getString("title"), resultSet.getInt("version"), resultSet.getInt("chunk_index"),
                resultSet.getString("source_url"), resultSet.getObject("source_file_id", UUID.class),
                resultSet.getString("source_filename"), resultSet.getString("content"),
                resultSet.getDouble("relevance"), vector(resultSet.getString("embedding"))));
        return candidates.stream()
                .map(candidate -> candidate.retrieved(queryEmbedding))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                        .thenComparing(RetrievedChunk::documentTitle)
                        .thenComparingInt(RetrievedChunk::chunkIndex))
                .limit(Math.min(Math.max(1, limit), 12))
                .toList();
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
                .addValue("sourceFileId", command.sourceFileId())
                .addValue("visibility", command.visibility())
                .addValue("status", command.status())
                .addValue("contentHash", command.contentHash())
                .addValue("actorSubject", command.actorSubject());
    }

    private MapSqlParameterSource chunkParams(
            UUID versionId,
            TextChunk chunk,
            double[] embedding,
            IngestCommand command) {
        return new MapSqlParameterSource()
                .addValue("versionId", versionId)
                .addValue("chunkIndex", chunk.index())
                .addValue("content", chunk.content())
                .addValue("contentHash", chunk.contentHash())
                .addValue("tokenCount", chunk.tokenCount())
                .addValue("metadata", "{}")
                .addValue("embeddingProvider", embedding == null ? null : command.embeddingProvider())
                .addValue("embeddingModel", embedding == null ? null : command.embeddingModel())
                .addValue("embedding", embedding == null ? null : json(embedding))
                .addValue("embeddingStatus", embedding == null ? "NOT_CONFIGURED" : "READY")
                .addValue("vectorDimension", embedding == null ? null : embedding.length)
                .addValue("embeddingUpdatedAt", embedding == null ? null : java.time.OffsetDateTime.now());
    }

    private double[] vector(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, double[].class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored knowledge embedding is invalid", exception);
        }
    }

    private static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) return 0;
        double dot = 0;
        double leftMagnitude = 0;
        double rightMagnitude = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftMagnitude += left[index] * left[index];
            rightMagnitude += right[index] * right[index];
        }
        return leftMagnitude == 0 || rightMagnitude == 0
                ? 0 : dot / Math.sqrt(leftMagnitude * rightMagnitude);
    }

    private record CandidateChunk(
            UUID chunkId, UUID documentId, String title, int version, int chunkIndex,
            String sourceUrl, UUID sourceFileId, String sourceFilename,
            String content, double lexicalScore, double[] embedding) {
        private RetrievedChunk retrieved(double[] queryEmbedding) {
            double semantic = Math.max(0, cosine(queryEmbedding, embedding));
            double combined = Math.min(99.999999,
                    semantic * 75 + Math.min(25, Math.max(0, lexicalScore)));
            return new RetrievedChunk(chunkId, documentId, title, version, chunkIndex,
                    sourceUrl, sourceFileId, sourceFilename, content, combined);
        }
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
