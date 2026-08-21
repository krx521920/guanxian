package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeRepository.IngestCommand;
import com.guanxian.platform.ai.rag.KnowledgeRepository.IngestionResult;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeIngestionService {
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "ASSOCIATION", "PRIVATE");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private final KnowledgeRepository repository;
    private final DocumentTextChunker chunker;

    public KnowledgeIngestionService(KnowledgeRepository repository, RagProperties properties) {
        this.repository = repository;
        this.chunker = new DocumentTextChunker(properties);
    }

    public IngestionResult ingest(KnowledgeTextDocument document) {
        if (document == null) throw new IllegalArgumentException("knowledge document is required");
        String visibility = normalized(document.visibility(), "ASSOCIATION");
        String status = normalized(document.status(), "PUBLISHED");
        if (!VISIBILITIES.contains(visibility)) throw new IllegalArgumentException("unsupported knowledge visibility");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("unsupported knowledge status");
        validateSourceUrl(document.sourceUrl());
        var chunks = chunker.split(document.content());
        if (chunks.isEmpty()) throw new IllegalArgumentException("knowledge document content is required");
        String contentHash = DocumentTextChunker.sha256(document.content().replace("\r\n", "\n").replace('\r', '\n').trim());
        return repository.ingest(new IngestCommand(
                document.documentId(), document.associationId(), document.title(), document.documentType(),
                document.sourceType(), document.sourceUrl(), visibility, status, document.actorSubject(),
                contentHash, chunks
        ));
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private void validateSourceUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return;
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("knowledge source URL is invalid", exception);
        }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("knowledge source URL must be HTTP(S) without user information");
        }
    }

    public record KnowledgeTextDocument(UUID documentId, UUID associationId, String title, String documentType,
                                        String sourceType, String sourceUrl, String visibility, String status,
                                        String actorSubject, String content) {
    }
}
