package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.DocumentTextChunker.TextChunk;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface KnowledgeRepository {
    IngestionResult ingest(IngestCommand command);
    List<RetrievedChunk> retrieve(UUID associationId, String query, int limit);
    UUID saveRetrieval(TraceDraft trace, List<CitationDraft> citations);
    UUID saveModelExecution(ModelExecutionDraft execution);

    record IngestCommand(UUID documentId, UUID associationId, String title, String documentType,
                         String sourceType, String sourceUrl, String visibility, String status,
                         String actorSubject, String contentHash, List<TextChunk> chunks) {
        public IngestCommand {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            if (title == null || title.isBlank() || documentType == null || documentType.isBlank()
                    || sourceType == null || sourceType.isBlank() || actorSubject == null || actorSubject.isBlank()
                    || contentHash == null || contentHash.length() != 64 || chunks.isEmpty()) {
                throw new IllegalArgumentException("knowledge ingestion command is invalid");
            }
            visibility = normalize(visibility, "ASSOCIATION");
            status = normalize(status, "PUBLISHED");
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
        }
    }

    record IngestionResult(UUID documentId, UUID documentVersionId, int version, int chunkCount, String contentHash) {
    }

    record RetrievedChunk(UUID chunkId, UUID documentId, String documentTitle, int documentVersion,
                          int chunkIndex, String sourceUrl, String content, double score) {
    }

    record TraceDraft(UUID associationId, String actorSubject, String question, String queryHash,
                      String provider, String model, String answerStatus, int inputTokens, int outputTokens,
                      BigDecimal estimatedCost, long latencyMs, String requestId) {
    }

    record CitationDraft(UUID chunkId, String quote, double score) {
    }

    record ModelExecutionDraft(UUID associationId, String actorSubject, String purpose, String provider,
                               String model, String status, String promptHash, int inputTokens, int outputTokens,
                               BigDecimal estimatedCost, long latencyMs, String errorCode, String requestId) {
    }
}
