package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.rag.KnowledgeDocumentParser;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeActor;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService.LifecycleAction;
import com.guanxian.platform.ai.rag.KnowledgeRepository.KnowledgeDocumentView;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import com.guanxian.platform.ai.rag.RagSecurityGuard.UnsafePromptException;
import com.guanxian.platform.ai.rag.RagEvaluationService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import com.guanxian.platform.storage.AttachmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\\\"([0-9]+)\\\"");

    private final KnowledgeIngestionService ingestionService;
    private final PolicyRagService ragService;
    private final ActorScopeResolver actorScopeResolver;
    private final KnowledgeDocumentParser documentParser;
    private final AttachmentService attachmentService;
    private final RagEvaluationService evaluationService;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            PolicyRagService ragService,
            ActorScopeResolver actorScopeResolver,
            KnowledgeDocumentParser documentParser,
            AttachmentService attachmentService) {
        this(ingestionService, ragService, actorScopeResolver, documentParser, attachmentService, null);
    }

    @Autowired
    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            PolicyRagService ragService,
            ActorScopeResolver actorScopeResolver,
            KnowledgeDocumentParser documentParser,
            AttachmentService attachmentService,
            RagEvaluationService evaluationService) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
        this.actorScopeResolver = actorScopeResolver;
        this.documentParser = documentParser;
        this.attachmentService = attachmentService;
        this.evaluationService = evaluationService;
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> documents(
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            Authentication authentication) {
        return ApiResponse.ok(ingestionService.listDocuments(actor(authentication), includeDeleted, page, size));
    }

    @GetMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> document(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        return documentResponse(ingestionService.getDocument(documentId, actor(authentication), includeDeleted));
    }

    @PostMapping("/documents/file")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> ingestFile(
            @Valid @RequestBody KnowledgeFileRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = writeAssociationId(request.associationId(), actor);
        try {
            AttachmentService.AttachmentDownload download = attachmentService.download(request.attachmentId(), actor);
            if (!associationId.equals(download.metadata().associationId())) {
                throw new ForbiddenException(
                        "KNOWLEDGE_SOURCE_SCOPE_MISMATCH",
                        "attachment does not belong to the selected association");
            }
            KnowledgeDocumentParser.ParsedDocument parsed = documentParser.parse(
                    download.metadata().originalFilename(), download.metadata().mediaType(), download.content());
            Object result = ingestionService.ingest(new KnowledgeIngestionService.KnowledgeTextDocument(
                    request.documentId(), associationId, request.title(),
                    defaultValue(request.documentType(), "POLICY"), "FILE", request.sourceUrl(),
                    defaultValue(request.visibility(), "ASSOCIATION"), "DRAFT",
                    actor.userId(), actor.subject(), actor.username(), parsed.text(),
                    download.metadata().id(), parsed.parserName(), parsed.parserVersion(), parsed.pageCount(),
                    MDC.get("requestId")));
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    @PostMapping("/documents/text")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> ingest(
            @Valid @RequestBody KnowledgeDocumentRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = writeAssociationId(request.associationId(), actor);
        try {
            Object result = ingestionService.ingest(new KnowledgeIngestionService.KnowledgeTextDocument(
                    request.documentId(), associationId, request.title(),
                    defaultValue(request.documentType(), "POLICY"),
                    defaultValue(request.sourceType(), "MANUAL_TEXT"), request.sourceUrl(),
                    defaultValue(request.visibility(), "ASSOCIATION"), "DRAFT",
                    actor.userId(), actor.subject(), actor.username(), request.content(),
                    null, null, null, null, MDC.get("requestId")));
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    @PostMapping("/documents/{documentId}/submit")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> submit(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return lifecycle(documentId, ifMatch, LifecycleAction.SUBMIT, false, null, authentication);
    }

    @PostMapping("/documents/{documentId}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> review(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        return lifecycle(documentId, ifMatch, LifecycleAction.REVIEW,
                request.approved(), request.comment(), authentication);
    }

    @PostMapping("/documents/{documentId}/disable")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> disable(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return lifecycle(documentId, ifMatch, LifecycleAction.DISABLE, false, null, authentication);
    }

    @PostMapping("/documents/{documentId}/archive")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> archive(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return lifecycle(documentId, ifMatch, LifecycleAction.ARCHIVE, false, null, authentication);
    }

    @DeleteMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> delete(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return lifecycle(documentId, ifMatch, LifecycleAction.DELETE, false, null, authentication);
    }

    @PostMapping("/documents/{documentId}/restore")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<KnowledgeDocumentView>> restore(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return lifecycle(documentId, ifMatch, LifecycleAction.RESTORE, false, null, authentication);
    }

    @PostMapping("/documents/{documentId}/reembed")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> reembed(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        try {
            return ApiResponse.ok(ingestionService.reembed(
                    documentId, requiredVersion(ifMatch), actor(authentication)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    @PostMapping("/documents/{documentId}/reparse")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> reparse(
            @PathVariable UUID documentId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        ActorScope actorScope = actorScopeResolver.resolve(authentication);
        KnowledgeActor actor = actor(actorScope);
        KnowledgeDocumentView current = ingestionService.getDocument(documentId, actor, false);
        if (current.lifecycleVersion() != requiredVersion(ifMatch)) {
            throw new com.guanxian.platform.shared.error.PreconditionFailedException(
                    "knowledge document version does not match If-Match");
        }
        if (current.sourceFileId() == null) {
            throw new ApiException("KNOWLEDGE_SOURCE_FILE_REQUIRED",
                    "only file-backed knowledge documents can be reparsed", HttpStatus.CONFLICT);
        }
        try {
            AttachmentService.AttachmentDownload download = attachmentService.download(current.sourceFileId(), actorScope);
            KnowledgeDocumentParser.ParsedDocument parsed = documentParser.parse(
                    download.metadata().originalFilename(), download.metadata().mediaType(), download.content());
            return ApiResponse.ok(ingestionService.ingest(new KnowledgeIngestionService.KnowledgeTextDocument(
                    current.id(), current.associationId(), current.title(), current.documentType(),
                    current.sourceType(), current.sourceUrl(), current.visibility(), "DRAFT",
                    actorScope.userId(), actorScope.subject(), actorScope.username(), parsed.text(),
                    current.sourceFileId(), parsed.parserName(), parsed.parserVersion(), parsed.pageCount(),
                    MDC.get("requestId"), current.lifecycleVersion())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    @PostMapping("/questions")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<PolicyRagService.RagAnswer> ask(
            @Valid @RequestBody KnowledgeQuestionRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = readAssociationId(request.associationId(), actor);
        try {
            return ApiResponse.ok(ragService.ask(new PolicyRagService.RagQuestion(
                    associationId, actor.subject(), request.question(), request.maxCitations(),
                    MDC.get("requestId"), actor.isSystemAdmin() || actor.isAssociationStaff())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    @GetMapping("/readiness")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<Object> readiness(Authentication authentication) {
        if (evaluationService == null) throw new IllegalStateException("RAG evaluation service is unavailable");
        ActorScope actor = actorScopeResolver.resolve(authentication);
        return ApiResponse.ok(evaluationService.readiness(readAssociationId(null, actor)));
    }

    @PostMapping("/evaluations")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> evaluate(
            @Valid @RequestBody EvaluationRequest request,
            Authentication authentication) {
        if (evaluationService == null) throw new IllegalStateException("RAG evaluation service is unavailable");
        try {
            return ApiResponse.ok(evaluationService.evaluate(
                    request.datasetName(),
                    request.cases().stream().map(value -> new RagEvaluationService.EvaluationCase(
                            value.question(), value.expectedDocumentIds(), value.expectRefusal())).toList(),
                    actor(authentication)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    private ResponseEntity<ApiResponse<KnowledgeDocumentView>> lifecycle(
            UUID documentId, List<String> ifMatch, LifecycleAction action,
            boolean approved, String comment, Authentication authentication) {
        try {
            KnowledgeDocumentView result = ingestionService.changeLifecycle(
                    documentId, requiredVersion(ifMatch), action, approved, comment, actor(authentication));
            return documentResponse(result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    private KnowledgeActor actor(Authentication authentication) {
        return actor(actorScopeResolver.resolve(authentication));
    }

    private KnowledgeActor actor(ActorScope actor) {
        UUID associationId = readAssociationId(null, actor);
        return new KnowledgeActor(associationId, actor.userId(), actor.subject(), actor.username(),
                actor.isSystemAdmin() || actor.isAssociationStaff(), MDC.get("requestId"));
    }

    private static ResponseEntity<ApiResponse<KnowledgeDocumentView>> documentResponse(
            KnowledgeDocumentView document) {
        return ResponseEntity.ok().eTag('"' + Long.toString(document.lifecycleVersion()) + '"')
                .body(ApiResponse.ok(document));
    }

    private static UUID writeAssociationId(UUID requested, ActorScope actor) {
        return readAssociationId(requested, actor);
    }

    private static UUID readAssociationId(UUID requested, ActorScope actor) {
        if (actor.associationId() == null) {
            throw new ForbiddenException(
                    "ASSOCIATION_CONTEXT_REQUIRED",
                    actor.isSystemAdmin()
                            ? "system administrator must select an association"
                            : "an association-bound identity is required");
        }
        if (requested != null && !requested.equals(actor.associationId())) {
            throw new ForbiddenException(
                    actor.isSystemAdmin() ? "SYSTEM_CONTEXT_FORBIDDEN" : "ASSOCIATION_SCOPE_VIOLATION",
                    "request association cannot override the selected association context");
        }
        return actor.associationId();
    }

    static long requiredVersion(List<String> ifMatch) {
        if (ifMatch == null || ifMatch.size() != 1) {
            if (ifMatch == null || ifMatch.isEmpty()) {
                throw new PreconditionRequiredException("If-Match header is required");
            }
            throw invalidIfMatch();
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(ifMatch.getFirst());
        if (!matcher.matches()) throw invalidIfMatch();
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalidIfMatch();
        }
    }

    private static ApiException invalidIfMatch() {
        return new ApiException(
                "INVALID_IF_MATCH", "If-Match must be one strong version ETag", HttpStatus.BAD_REQUEST);
    }

    private static ApiException invalidKnowledgeRequest(RuntimeException exception) {
        if (exception instanceof RagLimitException) {
            return new ApiException("RAG_LIMIT_EXCEEDED", exception.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (exception instanceof UnsafePromptException) {
            return new ApiException("UNSAFE_KNOWLEDGE_INPUT", exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
        if (exception instanceof IllegalStateException) {
            return new ApiException("KNOWLEDGE_OPERATION_UNAVAILABLE", exception.getMessage(), HttpStatus.CONFLICT);
        }
        return new ApiException("INVALID_KNOWLEDGE_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record KnowledgeDocumentRequest(
            UUID documentId, UUID associationId,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 64) String documentType,
            @Size(max = 64) String sourceType,
            @Size(max = 2000) String sourceUrl,
            @Size(max = 32) String visibility,
            @Size(max = 32) String status,
            @NotBlank @Size(max = 2_000_000) String content) {}

    public record KnowledgeQuestionRequest(
            UUID associationId,
            @NotBlank @Size(max = 2000) String question,
            @Min(1) @Max(12) Integer maxCitations) {}

    public record KnowledgeFileRequest(
            UUID documentId, UUID associationId,
            @NotNull UUID attachmentId,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 64) String documentType,
            @Size(max = 2000) String sourceUrl,
            @Size(max = 32) String visibility,
            @Size(max = 32) String status) {}

    public record ReviewRequest(boolean approved, @Size(max = 2000) String comment) {}

    public record EvaluationRequest(
            @NotBlank @Size(max = 200) String datasetName,
            @NotNull @Size(min = 1, max = 200) List<@Valid EvaluationCaseRequest> cases) {}

    public record EvaluationCaseRequest(
            @NotBlank @Size(max = 2000) String question,
            List<UUID> expectedDocumentIds,
            boolean expectRefusal) {}
}
