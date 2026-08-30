package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService;
import com.guanxian.platform.ai.rag.KnowledgeDocumentParser;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import com.guanxian.platform.ai.rag.RagSecurityGuard.UnsafePromptException;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import com.guanxian.platform.storage.AttachmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeIngestionService ingestionService;
    private final PolicyRagService ragService;
    private final ActorScopeResolver actorScopeResolver;
    private final KnowledgeDocumentParser documentParser;
    private final AttachmentService attachmentService;

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            PolicyRagService ragService,
            ActorScopeResolver actorScopeResolver,
            KnowledgeDocumentParser documentParser,
            AttachmentService attachmentService) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
        this.actorScopeResolver = actorScopeResolver;
        this.documentParser = documentParser;
        this.attachmentService = attachmentService;
    }

    @PostMapping("/documents/file")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> ingestFile(
            @Valid @RequestBody KnowledgeFileRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = writeAssociationId(request.associationId(), actor);
        try {
            AttachmentService.AttachmentDownload download = attachmentService.download(
                    request.attachmentId(), actor);
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
                    defaultValue(request.visibility(), "ASSOCIATION"), defaultValue(request.status(), "PUBLISHED"),
                    actor.userId(), actor.subject(), actor.username(), parsed.text(),
                    download.metadata().id(), parsed.parserName(), parsed.parserVersion(), parsed.pageCount()));
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException exception) {
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
                    request.documentId(),
                    associationId,
                    request.title(),
                    defaultValue(request.documentType(), "POLICY"),
                    defaultValue(request.sourceType(), "MANUAL_TEXT"),
                    request.sourceUrl(),
                    defaultValue(request.visibility(), "ASSOCIATION"),
                    defaultValue(request.status(), "PUBLISHED"),
                    actor.userId(), actor.subject(), actor.username(), request.content(),
                    null, null, null, null));
            return ApiResponse.ok(result);
        } catch (IllegalArgumentException exception) {
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
                    associationId,
                    actor.subject(),
                    request.question(),
                    request.maxCitations(),
                    MDC.get("requestId"),
                    actor.isSystemAdmin() || actor.isAssociationStaff())));
        } catch (IllegalArgumentException exception) {
            throw invalidKnowledgeRequest(exception);
        }
    }

    private static UUID writeAssociationId(UUID requested, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                throw new ForbiddenException(
                        "ASSOCIATION_CONTEXT_REQUIRED",
                        "system administrator must select an association before ingesting knowledge");
            }
            requireRequestMatchesSystemContext(requested, actor);
            return actor.associationId();
        }
        if (actor.associationId() == null) {
            throw new ForbiddenException(
                    "ASSOCIATION_CONTEXT_REQUIRED", "an association-bound identity is required");
        }
        return actor.associationId();
    }

    private static UUID readAssociationId(UUID requested, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            requireRequestMatchesSystemContext(requested, actor);
            return actor.associationId();
        }
        if (actor.associationId() == null) {
            throw new ForbiddenException(
                    "ASSOCIATION_CONTEXT_REQUIRED", "an association-bound identity is required");
        }
        return actor.associationId();
    }

    private static void requireRequestMatchesSystemContext(UUID requested, ActorScope actor) {
        if (requested != null && !requested.equals(actor.associationId())) {
            throw new ForbiddenException(
                    "SYSTEM_CONTEXT_FORBIDDEN",
                    "request association cannot override the selected system context");
        }
    }

    private static ApiException invalidKnowledgeRequest(IllegalArgumentException exception) {
        if (exception instanceof RagLimitException) {
            return new ApiException("RAG_LIMIT_EXCEEDED", exception.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (exception instanceof UnsafePromptException) {
            return new ApiException("UNSAFE_KNOWLEDGE_INPUT", exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
        return new ApiException("INVALID_KNOWLEDGE_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record KnowledgeDocumentRequest(
            UUID documentId,
            UUID associationId,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 64) String documentType,
            @Size(max = 64) String sourceType,
            @Size(max = 2000) String sourceUrl,
            @Size(max = 32) String visibility,
            @Size(max = 32) String status,
            @NotBlank @Size(max = 2_000_000) String content) {
    }

    public record KnowledgeQuestionRequest(
            UUID associationId,
            @NotBlank @Size(max = 2000) String question,
            @Min(1) @Max(12) Integer maxCitations) {
    }

    public record KnowledgeFileRequest(
            UUID documentId,
            UUID associationId,
            @jakarta.validation.constraints.NotNull UUID attachmentId,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 64) String documentType,
            @Size(max = 2000) String sourceUrl,
            @Size(max = 32) String visibility,
            @Size(max = 32) String status) {
    }
}
