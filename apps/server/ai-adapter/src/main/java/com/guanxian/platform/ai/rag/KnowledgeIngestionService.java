package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeRepository.IngestCommand;
import com.guanxian.platform.ai.rag.KnowledgeRepository.IngestionResult;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RagSecurityGuard securityGuard;
    private final EmbeddingProvider embeddingProvider;

    public KnowledgeIngestionService(KnowledgeRepository repository, RagProperties properties) {
        this(repository, properties, EmbeddingProvider.disabled());
    }

    @Autowired
    public KnowledgeIngestionService(
            KnowledgeRepository repository,
            RagProperties properties,
            EmbeddingProvider embeddingProvider) {
        this.repository = repository;
        this.chunker = new DocumentTextChunker(properties);
        this.securityGuard = new RagSecurityGuard(properties);
        this.embeddingProvider = embeddingProvider;
    }

    public IngestionResult ingest(KnowledgeTextDocument document) {
        if (document == null) throw new IllegalArgumentException("knowledge document is required");
        String visibility = normalized(document.visibility(), "ASSOCIATION");
        String status = normalized(document.status(), "PUBLISHED");
        if (!VISIBILITIES.contains(visibility)) throw new IllegalArgumentException("unsupported knowledge visibility");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("unsupported knowledge status");
        if (!"PUBLIC".equals(visibility) && document.associationId() == null) {
            throw new IllegalArgumentException("non-public knowledge documents require an association");
        }
        securityGuard.validateKnowledgeDocument(document.title(), document.sourceUrl(), document.content());
        validateSourceUrl(document.sourceUrl());
        var chunks = chunker.split(document.content());
        if (chunks.isEmpty()) throw new IllegalArgumentException("knowledge document content is required");
        var embeddings = embeddingProvider.enabled()
                ? embeddingProvider.embed(chunks.stream().map(DocumentTextChunker.TextChunk::content).toList())
                : java.util.List.<double[]>of();
        String contentHash = DocumentTextChunker.sha256(document.content().replace("\r\n", "\n").replace('\r', '\n').trim());
        return repository.ingest(new IngestCommand(
                document.documentId(), document.associationId(), document.title(), document.documentType(),
                document.sourceType(), document.sourceUrl(), visibility, status,
                document.actorUserId(), document.actorSubject(), document.actorUsername(),
                contentHash, chunks, document.sourceFileId(), document.parserName(), document.parserVersion(),
                document.pageCount(), embeddingProvider.enabled() ? embeddingProvider.providerName() : null,
                embeddingProvider.enabled() ? embeddingProvider.modelName() : null, embeddings
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
                                        UUID actorUserId, String actorSubject, String actorUsername,
                                        String content, UUID sourceFileId,
                                        String parserName, String parserVersion, Integer pageCount) {
        public KnowledgeTextDocument(UUID documentId, UUID associationId, String title, String documentType,
                                     String sourceType, String sourceUrl, String visibility, String status,
                                     String actorSubject, String content) {
            this(documentId, associationId, title, documentType, sourceType, sourceUrl, visibility, status,
                    null, actorSubject, actorSubject, content, null, null, null, null);
        }
    }
}
