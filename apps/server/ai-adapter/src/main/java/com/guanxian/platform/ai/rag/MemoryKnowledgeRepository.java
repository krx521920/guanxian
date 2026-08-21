package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.DocumentTextChunker.TextChunk;
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
        if (previous != null && !java.util.Objects.equals(previous.associationId(), command.associationId())) {
            throw new IllegalArgumentException("knowledge document does not exist in this association");
        }
        int version = previous == null ? 1 : previous.version() + 1;
        UUID versionId = UUID.randomUUID();
        MemoryDocument document = new MemoryDocument(documentId, command.associationId(), command.title(),
                command.sourceUrl(), command.visibility(), command.status(), command.actorSubject(),
                version, versionId, command.contentHash());
        documents.put(documentId, document);
        for (TextChunk source : command.chunks()) {
            UUID chunkId = UUID.randomUUID();
            chunks.put(chunkId, new MemoryChunk(chunkId, documentId, versionId, version, source.index(), source.content()));
        }
        return new IngestionResult(documentId, versionId, version, command.chunks().size(), command.contentHash());
    }

    @Override
    public synchronized List<RetrievedChunk> retrieve(RetrievalScope scope, String query, int limit) {
        if (query == null || query.isBlank() || limit < 1) return List.of();
        Set<String> terms = queryTerms(query);
        List<RetrievedChunk> matches = new ArrayList<>();
        for (MemoryChunk chunk : chunks.values()) {
            MemoryDocument document = documents.get(chunk.documentId());
            if (document == null || chunk.version() != document.version() || !"PUBLISHED".equals(document.status())) continue;
            if (!visibleTo(scope, document)) continue;
            double score = relevance(chunk.content(), query, terms);
            if (score > 0) {
                matches.add(new RetrievedChunk(chunk.id(), document.id(), document.title(), chunk.version(),
                        chunk.index(), document.sourceUrl(), chunk.content(), score));
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

    int retrievalTraceCount() { return retrievalTraces.size(); }
    int modelExecutionCount() { return modelExecutions.size(); }

    private boolean visibleTo(RetrievalScope scope, MemoryDocument document) {
        if ("PUBLIC".equals(document.visibility())) return true;
        if (scope.associationId() == null || !scope.associationId().equals(document.associationId())) return false;
        if ("ASSOCIATION".equals(document.visibility())) return true;
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

    private record MemoryDocument(UUID id, UUID associationId, String title, String sourceUrl,
                                  String visibility, String status, String createdBySubject,
                                  int version, UUID versionId, String contentHash) {
    }

    private record MemoryChunk(UUID id, UUID documentId, UUID versionId, int version, int index, String content) {
    }
}
