package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.DocumentTextChunker.TextChunk;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
public class MemoryKnowledgeRepository implements KnowledgeRepository {
    private final Map<UUID, MemoryDocument> documents = new HashMap<>();
    private final Map<UUID, MemoryChunk> chunks = new HashMap<>();
    private final List<UUID> retrievalTraces = new ArrayList<>();
    private final List<UUID> modelExecutions = new ArrayList<>();

    @Override
    public synchronized IngestionResult ingest(IngestCommand command) {
        UUID documentId = command.documentId() == null ? UUID.randomUUID() : command.documentId();
        MemoryDocument previous = documents.get(documentId);
        if (command.documentId() != null && previous == null) {
            throw new IllegalArgumentException("knowledge document does not exist in this association");
        }
        if (previous != null && !java.util.Objects.equals(previous.associationId(), command.associationId())) {
            throw new IllegalArgumentException("knowledge document does not exist in this association");
        }
        if (previous != null && command.expectedLifecycleVersion() != null
                && previous.lifecycleVersion() != command.expectedLifecycleVersion()) {
            throw new PreconditionFailedException("knowledge document version does not match If-Match");
        }
        int version = previous == null ? 1 : previous.version() + 1;
        String createdBySubject = previous == null ? command.actorSubject() : previous.createdBySubject();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();
        MemoryDocument document = new MemoryDocument(
                documentId, command.associationId(), command.title(), command.documentType(), command.sourceType(),
                command.sourceUrl(), command.sourceFileId(), command.visibility(), command.status(), createdBySubject,
                version, versionId, command.contentHash(), previous == null ? 0 : previous.lifecycleVersion() + 1,
                previous == null ? now : previous.createdAt(), now, null, null, null,
                previous == null ? null : previous.deletedAt(), previous == null ? null : previous.deletedBySubject());
        documents.put(documentId, document);
        for (int index = 0; index < command.chunks().size(); index++) {
            TextChunk source = command.chunks().get(index);
            UUID chunkId = UUID.randomUUID();
            double[] embedding = command.embeddings().isEmpty() ? null : command.embeddings().get(index);
            chunks.put(chunkId, new MemoryChunk(chunkId, documentId, versionId, version,
                    source.index(), source.content(), embedding));
        }
        return new IngestionResult(documentId, versionId, version, command.chunks().size(), command.contentHash(),
                command.embeddings().isEmpty() ? null : command.embeddingProvider(),
                command.embeddings().isEmpty() ? null : command.embeddingModel(), command.embeddingDimensions());
    }

    @Override
    public synchronized List<RetrievedChunk> retrieve(
            RetrievalScope scope,
            String query,
            double[] queryEmbedding,
            int limit) {
        if (query == null || query.isBlank() || limit < 1) return List.of();
        Set<String> terms = queryTerms(query);
        List<RetrievedChunk> matches = new ArrayList<>();
        for (MemoryChunk chunk : chunks.values()) {
            MemoryDocument document = documents.get(chunk.documentId());
            if (document == null || chunk.version() != document.version() || !"PUBLISHED".equals(document.status())) continue;
            if (!visibleTo(scope, document)) continue;
            double lexical = relevance(chunk.content(), query, terms);
            double semantic = Math.max(0, cosine(queryEmbedding, chunk.embedding()));
            double score = Math.min(99.999999,
                    semantic * 75 + Math.min(25, Math.max(0, lexical)));
            if (score > 0) {
                matches.add(new RetrievedChunk(chunk.id(), document.id(), document.title(), chunk.version(),
                        chunk.index(), document.sourceUrl(), document.sourceFileId(), null, chunk.content(), score));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                        .thenComparing(RetrievedChunk::documentTitle)
                        .thenComparingInt(RetrievedChunk::chunkIndex))
                .limit(Math.min(limit, 12))
                .toList();
    }

    @Override
    public synchronized UUID saveRetrieval(TraceDraft trace, List<CitationDraft> citations) {
        UUID id = UUID.randomUUID();
        retrievalTraces.add(id);
        return id;
    }

    @Override
    public synchronized UUID saveModelExecution(ModelExecutionDraft execution) {
        UUID id = UUID.randomUUID();
        modelExecutions.add(id);
        return id;
    }

    @Override
    public synchronized KnowledgeDocumentPage listDocuments(
            DocumentScope scope, boolean includeDeleted, int offset, int limit) {
        List<KnowledgeDocumentView> visible = documents.values().stream()
                .filter(document -> scope.associationId().equals(document.associationId()))
                .filter(document -> includeDeleted || document.deletedAt() == null)
                .filter(document -> scope.privileged() || !"PRIVATE".equals(document.visibility())
                        || scope.actorSubject().equals(document.createdBySubject()))
                .sorted(Comparator.comparing(MemoryDocument::updatedAt).reversed()
                        .thenComparing(MemoryDocument::id))
                .map(this::view)
                .toList();
        int from = Math.min(Math.max(0, offset), visible.size());
        int to = Math.min(visible.size(), from + Math.max(1, limit));
        int size = Math.max(1, limit);
        return new KnowledgeDocumentPage(visible.subList(from, to), visible.size(), from / size, size);
    }

    @Override
    public synchronized java.util.Optional<KnowledgeDocumentView> findDocument(
            UUID documentId, DocumentScope scope, boolean includeDeleted) {
        MemoryDocument document = documents.get(documentId);
        if (document == null || !scope.associationId().equals(document.associationId())
                || !includeDeleted && document.deletedAt() != null
                || !scope.privileged() && "PRIVATE".equals(document.visibility())
                && !scope.actorSubject().equals(document.createdBySubject())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(view(document));
    }

    @Override
    public synchronized KnowledgeDocumentView updateLifecycle(LifecycleCommand command) {
        MemoryDocument current = documents.get(command.documentId());
        if (current == null || !command.associationId().equals(current.associationId())
                || current.lifecycleVersion() != command.expectedVersion()) {
            throw new IllegalStateException("knowledge document changed or is outside the selected association");
        }
        Instant now = Instant.now();
        boolean reviewed = command.action().startsWith("KNOWLEDGE_REVIEW_");
        boolean clearReview = "KNOWLEDGE_SUBMIT".equals(command.action())
                || "KNOWLEDGE_RESTORE".equals(command.action());
        MemoryDocument updated = new MemoryDocument(
                current.id(), current.associationId(), current.title(), current.documentType(), current.sourceType(),
                current.sourceUrl(), current.sourceFileId(), current.visibility(),
                command.targetStatus() == null ? current.status() : command.targetStatus(),
                current.createdBySubject(), current.version(), current.versionId(), current.contentHash(),
                current.lifecycleVersion() + 1, current.createdAt(), now,
                reviewed ? command.actorSubject() : clearReview ? null : current.reviewedBySubject(),
                reviewed ? now : clearReview ? null : current.reviewedAt(),
                reviewed ? command.reviewComment() : clearReview ? null : current.reviewComment(),
                command.restore() ? null : command.delete() ? now : current.deletedAt(),
                command.restore() ? null : command.delete() ? command.actorSubject() : current.deletedBySubject());
        documents.put(updated.id(), updated);
        return view(updated);
    }

    @Override
    public synchronized DocumentContent currentContent(UUID documentId, DocumentScope scope) {
        KnowledgeDocumentView document = findDocument(documentId, scope, false)
                .orElseThrow(() -> new IllegalArgumentException("knowledge document does not exist in this association"));
        MemoryDocument stored = documents.get(documentId);
        List<ChunkSource> sources = chunks.values().stream()
                .filter(chunk -> documentId.equals(chunk.documentId()) && chunk.version() == stored.version())
                .sorted(Comparator.comparingInt(MemoryChunk::index))
                .map(chunk -> new ChunkSource(chunk.id(), chunk.index(), chunk.content()))
                .toList();
        return new DocumentContent(document, sources);
    }

    @Override
    public synchronized ReembeddingResult replaceEmbeddings(ReembeddingCommand command) {
        DocumentContent content = currentContent(command.documentId(),
                new DocumentScope(command.associationId(), command.actorSubject(), true));
        if (content.document().lifecycleVersion() != command.expectedVersion()
                || content.chunks().size() != command.embeddings().size()) {
            throw new IllegalStateException("knowledge document changed or embedding count does not match");
        }
        for (int index = 0; index < content.chunks().size(); index++) {
            ChunkSource source = content.chunks().get(index);
            MemoryChunk current = chunks.get(source.id());
            chunks.put(source.id(), new MemoryChunk(current.id(), current.documentId(), current.versionId(),
                    current.version(), current.index(), current.content(), command.embeddings().get(index)));
        }
        MemoryDocument current = documents.get(command.documentId());
        MemoryDocument updated = new MemoryDocument(
                current.id(), current.associationId(), current.title(), current.documentType(), current.sourceType(),
                current.sourceUrl(), current.sourceFileId(), current.visibility(), current.status(),
                current.createdBySubject(), current.version(), current.versionId(), current.contentHash(),
                current.lifecycleVersion() + 1, current.createdAt(), Instant.now(),
                current.reviewedBySubject(), current.reviewedAt(), current.reviewComment(),
                current.deletedAt(), current.deletedBySubject());
        documents.put(updated.id(), updated);
        int dimensions = command.embeddings().isEmpty() ? 0 : command.embeddings().getFirst().length;
        return new ReembeddingResult(updated.id(), updated.version(), content.chunks().size(),
                command.provider(), command.model(), dimensions, updated.lifecycleVersion());
    }

    private KnowledgeDocumentView view(MemoryDocument document) {
        int chunkCount = (int) chunks.values().stream()
                .filter(chunk -> document.id().equals(chunk.documentId()) && chunk.version() == document.version()).count();
        boolean embedded = chunks.values().stream()
                .filter(chunk -> document.id().equals(chunk.documentId()) && chunk.version() == document.version())
                .anyMatch(chunk -> chunk.embedding() != null);
        return new KnowledgeDocumentView(
                document.id(), document.associationId(), document.title(), document.documentType(),
                document.sourceType(), document.sourceUrl(), document.sourceFileId(), null,
                document.visibility(), document.status(), document.version(), chunkCount,
                embedded ? "READY" : "NOT_CONFIGURED", document.lifecycleVersion(),
                document.createdBySubject(), document.createdAt(), document.updatedAt(),
                document.reviewedBySubject(), document.reviewedAt(), document.reviewComment(),
                document.deletedAt(), document.deletedBySubject());
    }

    int retrievalTraceCount() { return retrievalTraces.size(); }
    int modelExecutionCount() { return modelExecutions.size(); }

    private boolean visibleTo(RetrievalScope scope, MemoryDocument document) {
        if (!scope.associationId().equals(document.associationId())) return false;
        if ("PUBLIC".equals(document.visibility()) || "ASSOCIATION".equals(document.visibility())) return true;
        return "PRIVATE".equals(document.visibility())
                && (scope.privileged() || scope.actorSubject().equals(document.createdBySubject()));
    }

    static Set<String> queryTerms(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}，。！？；：、（）【】《》]+", " ").trim();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (!normalized.isBlank()) terms.add(normalized);
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) terms.add(part);
            if (part.length() > 4) {
                for (int size : new int[]{4, 3, 2}) {
                    for (int offset = 0; offset + size <= part.length() && terms.size() < 20; offset++) {
                        terms.add(part.substring(offset, offset + size));
                    }
                }
            }
        }
        return terms;
    }

    private double relevance(String content, String query, Set<String> terms) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();
        double score = normalizedContent.contains(normalizedQuery) ? 10 : 0;
        int weight = 4;
        for (String term : terms) {
            if (term.length() >= 2 && normalizedContent.contains(term)) score += weight;
            if (weight > 1) weight--;
        }
        return score;
    }

    private double cosine(double[] left, double[] right) {
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

    private record MemoryDocument(
            UUID id, UUID associationId, String title, String documentType, String sourceType,
            String sourceUrl, UUID sourceFileId, String visibility, String status, String createdBySubject,
            int version, UUID versionId, String contentHash, long lifecycleVersion,
            Instant createdAt, Instant updatedAt, String reviewedBySubject, Instant reviewedAt,
            String reviewComment, Instant deletedAt, String deletedBySubject) {
    }

    private record MemoryChunk(UUID id, UUID documentId, UUID versionId, int version, int index,
                               String content, double[] embedding) {
        private MemoryChunk {
            embedding = embedding == null ? null : embedding.clone();
        }
    }
}
