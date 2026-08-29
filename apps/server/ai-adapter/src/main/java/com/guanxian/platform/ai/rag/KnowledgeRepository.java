package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.DocumentTextChunker.TextChunk;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface KnowledgeRepository {
    IngestionResult ingest(IngestCommand command);
    List<RetrievedChunk> retrieve(RetrievalScope scope, String query, double[] queryEmbedding, int limit);
    default List<RetrievedChunk> retrieve(RetrievalScope scope, String query, int limit) {
        return retrieve(scope, query, null, limit);
    }
    UUID saveRetrieval(TraceDraft trace, List<CitationDraft> citations);
    UUID saveModelExecution(ModelExecutionDraft execution);

    record RetrievalScope(UUID associationId, String actorSubject, boolean privileged) {
        public RetrievalScope {
            if (actorSubject == null || actorSubject.isBlank()) {
                throw new IllegalArgumentException("actor subject is required for knowledge retrieval");
            }
        }
    }

    record IngestCommand(UUID documentId, UUID associationId, String title, String documentType,
                         String sourceType, String sourceUrl, String visibility, String status,
                         UUID actorUserId, String actorSubject, String actorUsername,
                         String contentHash, List<TextChunk> chunks,
                         UUID sourceFileId, String parserName, String parserVersion, Integer pageCount,
                         String embeddingProvider, String embeddingModel, List<double[]> embeddings) {
        public IngestCommand(
                UUID documentId, UUID associationId, String title, String documentType,
                String sourceType, String sourceUrl, String visibility, String status,
                String actorSubject, String contentHash, List<TextChunk> chunks) {
            this(documentId, associationId, title, documentType, sourceType, sourceUrl, visibility, status,
                    null, actorSubject, actorSubject, contentHash, chunks,
                    null, null, null, null, null, null, List.of());
        }

        public IngestCommand {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            embeddings = embeddings == null ? List.of() : embeddings.stream()
                    .map(vector -> vector == null ? null : vector.clone())
                    .toList();
            if (title == null || title.isBlank() || documentType == null || documentType.isBlank()
                    || sourceType == null || sourceType.isBlank() || actorSubject == null || actorSubject.isBlank()
                    || contentHash == null || contentHash.length() != 64 || chunks.isEmpty()) {
                throw new IllegalArgumentException("knowledge ingestion command is invalid");
            }
            if (pageCount != null && pageCount < 1) {
                throw new IllegalArgumentException("knowledge page count must be positive");
            }
            if (!embeddings.isEmpty()) {
                if (embeddings.size() != chunks.size() || embeddingProvider == null || embeddingProvider.isBlank()
                        || embeddingModel == null || embeddingModel.isBlank()) {
                    throw new IllegalArgumentException("knowledge embeddings do not match chunks");
                }
                int dimensions = validateVector(embeddings.getFirst());
                for (double[] vector : embeddings) {
                    if (validateVector(vector) != dimensions) {
                        throw new IllegalArgumentException("knowledge embedding dimensions are inconsistent");
                    }
                }
            }
            visibility = normalize(visibility, "ASSOCIATION");
            status = normalize(status, "PUBLISHED");
        }

        public int embeddingDimensions() {
            return embeddings.isEmpty() ? 0 : embeddings.getFirst().length;
        }

        private static int validateVector(double[] vector) {
            if (vector == null || vector.length < 8 || vector.length > 4096) {
                throw new IllegalArgumentException("knowledge embedding dimensions are invalid");
            }
            double magnitude = 0;
            for (double value : vector) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("knowledge embedding contains a non-finite value");
                }
                magnitude += value * value;
            }
            if (magnitude == 0) throw new IllegalArgumentException("knowledge embedding must not be zero");
            return vector.length;
        }

        private static String normalize(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
        }
    }

    record IngestionResult(UUID documentId, UUID documentVersionId, int version, int chunkCount,
                           String contentHash, String embeddingProvider, String embeddingModel,
                           int embeddingDimensions) {
        public IngestionResult(UUID documentId, UUID documentVersionId, int version, int chunkCount,
                               String contentHash) {
            this(documentId, documentVersionId, version, chunkCount, contentHash, null, null, 0);
        }
    }

    record RetrievedChunk(UUID chunkId, UUID documentId, String documentTitle, int documentVersion,
                          int chunkIndex, String sourceUrl, UUID sourceFileId, String sourceFilename,
                          String content, double score) {
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
