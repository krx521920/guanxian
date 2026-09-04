package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.PlatformAssistantService;
import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import com.guanxian.platform.ai.rag.RagSecurityGuard.UnsafePromptException;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final PlatformAssistantService assistantService;
    private final ActorScopeResolver actorScopeResolver;

    public AssistantController(
            PlatformAssistantService assistantService,
            ActorScopeResolver actorScopeResolver) {
        this.assistantService = assistantService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<PlatformAssistantService.AssistantAnswer> chat(
            @Valid @RequestBody AssistantChatRequest request,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = readAssociationId(request.associationId(), actor);
        try {
            return ApiResponse.ok(assistantService.chat(new PlatformAssistantService.AssistantQuestion(
                    associationId,
                    actor.subject(),
                    request.conversationId(),
                    request.message(),
                    request.maxCitations(),
                    request.pageTitle(),
                    request.pagePath(),
                    MDC.get("requestId"),
                    actor.isSystemAdmin() || actor.isAssociationStaff())));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidAssistantRequest(exception);
        }
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

    private static ApiException invalidAssistantRequest(RuntimeException exception) {
        if (exception instanceof RagLimitException) {
            return new ApiException("RAG_LIMIT_EXCEEDED", exception.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (exception instanceof UnsafePromptException) {
            return new ApiException("UNSAFE_KNOWLEDGE_INPUT", exception.getMessage(), HttpStatus.BAD_REQUEST);
        }
        if (exception instanceof IllegalStateException) {
            return new ApiException("ASSISTANT_UNAVAILABLE", "智能助手暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new ApiException("INVALID_ASSISTANT_REQUEST", exception.getMessage(), HttpStatus.BAD_REQUEST);
    }

    public record AssistantChatRequest(
            UUID associationId,
            @NotNull UUID conversationId,
            @NotBlank @Size(max = 2000) String message,
            @Min(1) @Max(12) Integer maxCitations,
            @NotBlank @Size(max = 100) String pageTitle,
            @NotBlank @Size(max = 300) String pagePath) {
    }
}
