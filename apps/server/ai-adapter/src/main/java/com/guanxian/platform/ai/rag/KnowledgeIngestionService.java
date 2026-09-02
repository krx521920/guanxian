package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeRepository.DocumentScope;
import com.guanxian.platform.ai.rag.KnowledgeRepository.IngestCommand;
import com.guanxian.platform.ai.rag.KnowledgeRepository.IngestionResult;
import com.guanxian.platform.ai.rag.KnowledgeRepository.KnowledgeDocumentPage;
import com.guanxian.platform.ai.rag.KnowledgeRepository.KnowledgeDocumentView;
import com.guanxian.platform.ai.rag.KnowledgeRepository.LifecycleCommand;
import com.guanxian.platform.ai.rag.KnowledgeRepository.ModelExecutionDraft;
import com.guanxian.platform.ai.rag.KnowledgeRepository.ReembeddingCommand;
import com.guanxian.platform.ai.rag.KnowledgeRepository.ReembeddingResult;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeIngestionService {
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "ASSOCIATION", "PRIVATE");
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "PUBLISHED", "DISABLED", "ARCHIVED");

    private final KnowledgeRepository repository;
    private final DocumentTextChunker chunker;
    private final RagSecurityGuard securityGuard;
    private final EmbeddingProvider embeddingProvider;
    private final RagProperties properties;
    private final EmbeddingProperties embeddingProperties;

    public KnowledgeIngestionService(KnowledgeRepository repository, RagProperties properties) {
        this(repository, properties, EmbeddingProvider.disabled(), new EmbeddingProperties());
    }

    public KnowledgeIngestionService(
            KnowledgeRepository repository,
            RagProperties properties,
            EmbeddingProvider embeddingProvider) {
        this(repository, properties, embeddingProvider, new EmbeddingProperties());
    }

    @Autowired
    public KnowledgeIngestionService(
            KnowledgeRepository repository,
            RagProperties properties,
            EmbeddingProvider embeddingProvider,
            EmbeddingProperties embeddingProperties) {
        properties.validate();
        if (embeddingProperties.getCostPerMillionTokens().signum() < 0) {
            throw new IllegalStateException("embedding token cost must be non-negative");
        }
        this.repository = repository;
        this.chunker = new DocumentTextChunker(properties);
        this.securityGuard = new RagSecurityGuard(properties);
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
    }

    public IngestionResult ingest(KnowledgeTextDocument document) {
        if (document == null) throw new IllegalArgumentException("knowledge document is required");
        if (document.associationId() == null) {
            throw new IllegalArgumentException("knowledge document association is required");
        }
        String visibility = normalized(document.visibility(), "ASSOCIATION");
        String status = normalized(document.status(), "DRAFT");
        if (!VISIBILITIES.contains(visibility)) throw new IllegalArgumentException("unsupported knowledge visibility");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("unsupported knowledge status");
        securityGuard.validateKnowledgeDocument(document.title(), document.sourceUrl(), document.content());
        validateSourceUrl(document.sourceUrl());
        var chunks = chunker.split(document.content());
        if (chunks.isEmpty()) throw new IllegalArgumentException("knowledge document content is required");
        List<double[]> embeddings = embed(
                document.associationId(), document.actorSubject(), "KNOWLEDGE_INGEST_EMBEDDING",
                chunks.stream().map(DocumentTextChunker.TextChunk::content).toList(), document.requestId());
        String contentHash = DocumentTextChunker.sha256(
                document.content().replace("\r\n", "\n").replace('\r', '\n').trim());
        return repository.ingest(new IngestCommand(
                document.documentId(), document.associationId(), document.title(), document.documentType(),
                document.sourceType(), document.sourceUrl(), visibility, status,
                document.actorUserId(), document.actorSubject(), document.actorUsername(),
                contentHash, chunks, document.sourceFileId(), document.parserName(), document.parserVersion(),
                document.pageCount(), embeddings.isEmpty() ? null : embeddingProvider.providerName(),
                embeddings.isEmpty() ? null : embeddingProvider.modelName(), embeddings,
                document.expectedLifecycleVersion()
        ));
    }

    public KnowledgeDocumentPage listDocuments(
            KnowledgeActor actor, boolean includeDeleted, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        return repository.listDocuments(scope(actor), includeDeleted, safePage * safeSize, safeSize);
    }

    public KnowledgeDocumentView getDocument(UUID documentId, KnowledgeActor actor, boolean includeDeleted) {
        return repository.findDocument(documentId, scope(actor), includeDeleted)
                .orElseThrow(() -> new IllegalArgumentException(
                        "knowledge document does not exist in the selected association"));
    }

    public KnowledgeDocumentView changeLifecycle(
            UUID documentId,
            long expectedVersion,
            LifecycleAction action,
            boolean approved,
            String comment,
            KnowledgeActor actor) {
        KnowledgeDocumentView current = getDocument(documentId, actor, true);
        if (current.lifecycleVersion() != expectedVersion) {
            throw new PreconditionFailedException("knowledge document version does not match If-Match");
        }
        Transition transition = transition(current, action, approved, comment);
        return repository.updateLifecycle(new LifecycleCommand(
                documentId, actor.associationId(), expectedVersion, transition.targetStatus(),
                transition.delete(), transition.restore(), normalizedComment(comment),
                actor.userId(), actor.subject(), actor.username(), transition.auditAction(), actor.requestId()));
    }

    public ReembeddingResult reembed(UUID documentId, long expectedVersion, KnowledgeActor actor) {
        KnowledgeRepository.DocumentContent content = repository.currentContent(documentId, scope(actor));
        if (content.document().lifecycleVersion() != expectedVersion) {
            throw new PreconditionFailedException("knowledge document version does not match If-Match");
        }
        if (!embeddingProvider.enabled()) {
            throw new IllegalStateException("embedding provider is not enabled");
        }
        List<double[]> embeddings = embed(
                actor.associationId(), actor.subject(), "KNOWLEDGE_REEMBEDDING",
                content.chunks().stream().map(KnowledgeRepository.ChunkSource::content).toList(), actor.requestId());
        return repository.replaceEmbeddings(new ReembeddingCommand(
                documentId, actor.associationId(), expectedVersion,
                embeddingProvider.providerName(), embeddingProvider.modelName(), embeddings,
                actor.userId(), actor.subject(), actor.username(), actor.requestId()));
    }

    private Transition transition(
            KnowledgeDocumentView current, LifecycleAction action, boolean approved, String comment) {
        if (action != LifecycleAction.RESTORE && current.deleted()) {
            throw new IllegalArgumentException("deleted knowledge document must be restored first");
        }
        return switch (action) {
            case SUBMIT -> {
                requireStatus(current, Set.of("DRAFT"), "only draft documents can be submitted");
                yield new Transition("PENDING_REVIEW", false, false, "KNOWLEDGE_SUBMIT");
            }
            case REVIEW -> {
                requireStatus(current, Set.of("PENDING_REVIEW"), "only pending documents can be reviewed");
                if (!approved && (comment == null || comment.isBlank())) {
                    throw new IllegalArgumentException("review rejection comment is required");
                }
                yield new Transition(approved ? "PUBLISHED" : "DRAFT", false, false,
                        approved ? "KNOWLEDGE_REVIEW_APPROVE" : "KNOWLEDGE_REVIEW_REJECT");
            }
            case DISABLE -> {
                requireStatus(current, Set.of("PUBLISHED"), "only published documents can be disabled");
                yield new Transition("DISABLED", false, false, "KNOWLEDGE_DISABLE");
            }
            case ARCHIVE -> {
                requireStatus(current, Set.of("PUBLISHED", "DISABLED"),
                        "only published or disabled documents can be archived");
                yield new Transition("ARCHIVED", false, false, "KNOWLEDGE_ARCHIVE");
            }
            case DELETE -> {
                if (current.deleted()) throw new IllegalArgumentException("knowledge document is already deleted");
                yield new Transition(current.status(), true, false, "KNOWLEDGE_DELETE");
            }
            case RESTORE -> {
                if (!current.deleted()) throw new IllegalArgumentException("knowledge document is not deleted");
                yield new Transition("DRAFT", false, true, "KNOWLEDGE_RESTORE");
            }
        };
    }

    private List<double[]> embed(
            UUID associationId, String actorSubject, String purpose, List<String> texts, String requestId) {
        if (!embeddingProvider.enabled()) return List.of();
        if (!properties.isExternalModelDataEgressEnabled()) {
            throw new IllegalStateException("external model data egress is not approved");
        }
        int inputTokens = texts.stream().mapToInt(DocumentTextChunker::estimateTokens).sum();
        BigDecimal estimatedCost = BigDecimal.valueOf(inputTokens)
                .multiply(embeddingProperties.getCostPerMillionTokens())
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
        if (estimatedCost.compareTo(properties.getMaxEstimatedCost()) > 0) {
            throw new PolicyRagService.RagLimitException(
                    "estimated embedding cost exceeds the configured request limit");
        }
        String contentHash = DocumentTextChunker.sha256(String.join("\n", texts));
        long started = System.nanoTime();
        try {
            List<double[]> result = embeddingProvider.embed(texts);
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            repository.saveModelExecution(new ModelExecutionDraft(
                    associationId, actorSubject, purpose, embeddingProvider.providerName(),
                    embeddingProvider.modelName(), "SUCCEEDED", contentHash, inputTokens, 0,
                    estimatedCost, latency, null, requestId));
            return result;
        } catch (RuntimeException exception) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            repository.saveModelExecution(new ModelExecutionDraft(
                    associationId, actorSubject, purpose, embeddingProvider.providerName(),
                    embeddingProvider.modelName(), "FAILED", contentHash, inputTokens, 0,
                    BigDecimal.ZERO, latency, exception.getClass().getSimpleName(), requestId));
            throw exception;
        }
    }

    private DocumentScope scope(KnowledgeActor actor) {
        if (actor == null) throw new IllegalArgumentException("knowledge actor is required");
        return new DocumentScope(actor.associationId(), actor.subject(), actor.privileged());
    }

    private void requireStatus(KnowledgeDocumentView document, Set<String> allowed, String message) {
        if (!allowed.contains(document.status())) throw new IllegalArgumentException(message);
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizedComment(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    public enum LifecycleAction { SUBMIT, REVIEW, DISABLE, ARCHIVE, DELETE, RESTORE }

    private record Transition(String targetStatus, boolean delete, boolean restore, String auditAction) {}

    public record KnowledgeActor(
            UUID associationId, UUID userId, String subject, String username,
            boolean privileged, String requestId) {
        public KnowledgeActor {
            if (associationId == null || subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("association and actor subject are required");
            }
        }
    }

    public record KnowledgeTextDocument(
            UUID documentId, UUID associationId, String title, String documentType,
            String sourceType, String sourceUrl, String visibility, String status,
            UUID actorUserId, String actorSubject, String actorUsername,
            String content, UUID sourceFileId,
            String parserName, String parserVersion, Integer pageCount, String requestId,
            Long expectedLifecycleVersion) {
        public KnowledgeTextDocument(
                UUID documentId, UUID associationId, String title, String documentType,
                String sourceType, String sourceUrl, String visibility, String status,
                UUID actorUserId, String actorSubject, String actorUsername,
                String content, UUID sourceFileId,
                String parserName, String parserVersion, Integer pageCount, String requestId) {
            this(documentId, associationId, title, documentType, sourceType, sourceUrl, visibility, status,
                    actorUserId, actorSubject, actorUsername, content, sourceFileId,
                    parserName, parserVersion, pageCount, requestId, null);
        }

        public KnowledgeTextDocument(
                UUID documentId, UUID associationId, String title, String documentType,
                String sourceType, String sourceUrl, String visibility, String status,
                UUID actorUserId, String actorSubject, String actorUsername,
                String content, UUID sourceFileId,
                String parserName, String parserVersion, Integer pageCount) {
            this(documentId, associationId, title, documentType, sourceType, sourceUrl, visibility, status,
                    actorUserId, actorSubject, actorUsername, content, sourceFileId,
                    parserName, parserVersion, pageCount, null, null);
        }

        public KnowledgeTextDocument(
                UUID documentId, UUID associationId, String title, String documentType,
                String sourceType, String sourceUrl, String visibility, String status,
                String actorSubject, String content) {
            this(documentId, associationId, title, documentType, sourceType, sourceUrl, visibility, status,
                    null, actorSubject, actorSubject, content, null, null, null, null, null, null);
        }
    }
}
