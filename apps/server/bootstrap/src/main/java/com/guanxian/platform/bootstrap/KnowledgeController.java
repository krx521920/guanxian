package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
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

    public KnowledgeController(
            KnowledgeIngestionService ingestionService,
            PolicyRagService ragService,
            ActorScopeResolver actorScopeResolver) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @PostMapping("/documents/text")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ApiResponse<Object> ingest(
            @Valid @RequestBody KnowledgeDocumentRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = associationId(request.associationId(), actor);
        Object result = ingestionService.ingest(new KnowledgeIngestionService.KnowledgeTextDocument(
                request.documentId(),
                associationId,
                request.title(),
                defaultValue(request.documentType(), "POLICY"),
                defaultValue(request.sourceType(), "MANUAL_TEXT"),
                request.sourceUrl(),
                defaultValue(request.visibility(), "ASSOCIATION"),
                defaultValue(request.status(), "PUBLISHED"),
                actor.subject(),
                request.content()));
        return ApiResponse.ok(result);
    }

    @PostMapping("/questions")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<PolicyRagService.RagAnswer> ask(
            @Valid @RequestBody KnowledgeQuestionRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = associationId(request.associationId(), actor);
        return ApiResponse.ok(ragService.ask(new PolicyRagService.RagQuestion(
                associationId,
                actor.subject(),
                request.question(),
                request.maxCitations(),
                MDC.get("requestId"))));
    }

    private static UUID associationId(UUID requested, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            if (requested == null) {
                throw new ForbiddenException(
                        "ASSOCIATION_CONTEXT_REQUIRED",
                        "system administrator must specify the target association");
            }
            return requested;
        }
        if (actor.associationId() == null) {
            throw new ForbiddenException(
                    "ASSOCIATION_CONTEXT_REQUIRED", "an association-bound identity is required");
        }
        return actor.associationId();
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
}
