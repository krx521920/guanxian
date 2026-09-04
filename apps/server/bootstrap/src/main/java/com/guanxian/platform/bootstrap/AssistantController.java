package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.AssistantAccessContext;
import com.guanxian.platform.ai.assistant.PlatformAssistantService;
import com.guanxian.platform.ai.assistant.PlatformAssistantService.AssistantStreamEvent;
import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import com.guanxian.platform.ai.rag.RagSecurityGuard.UnsafePromptException;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;

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
            return ApiResponse.ok(assistantService.chat(question(request, authentication, actor, associationId)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw invalidAssistantRequest(exception);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('POLICY_READ')")
    Flux<AssistantStreamEvent> stream(
            @Valid @RequestBody AssistantChatRequest request,
            Authentication authentication,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ActorScope actor = actorScopeResolver.resolve(authentication);
        UUID associationId = readAssociationId(request.associationId(), actor);
        return assistantService.stream(question(request, authentication, actor, associationId))
                .onErrorResume(RuntimeException.class, exception -> Flux.just(streamError(
                        request.conversationId(), exception)));
    }

    private static PlatformAssistantService.AssistantQuestion question(
            AssistantChatRequest request,
            Authentication authentication,
            ActorScope actor,
            UUID associationId) {
        ActorScope scopedActor = associationId.equals(actor.associationId())
                ? actor
                : new ActorScope(
                        actor.userId(), actor.subject(), actor.username(), associationId,
                        actor.enterpriseId(), actor.roles(), actor.partnerAssociationIds());
        AssistantAccessContext access = new AssistantAccessContext(
                scopedActor,
                authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .collect(Collectors.toUnmodifiableSet()));
        return new PlatformAssistantService.AssistantQuestion(
                access, request.conversationId(), request.message(), request.maxCitations(),
                request.pageTitle(), request.pagePath(), MDC.get("requestId"));
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

    private static AssistantStreamEvent streamError(UUID conversationId, RuntimeException exception) {
        ApiException mapped;
        if (exception instanceof RagLimitException
                || exception instanceof UnsafePromptException
                || exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException) {
            mapped = invalidAssistantRequest(exception);
        } else {
            mapped = new ApiException(
                    "ASSISTANT_STREAM_FAILED",
                    "智能助手暂时不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        return AssistantStreamEvent.error(conversationId, mapped.code(), mapped.getMessage());
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
