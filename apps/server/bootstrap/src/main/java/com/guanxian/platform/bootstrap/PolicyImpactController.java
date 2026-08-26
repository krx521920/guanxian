package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisService;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ImpactActor;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisView;
import com.guanxian.platform.ai.impact.PolicyImpactException;
import com.guanxian.platform.ai.impact.PolicyImpactHistoryView;
import com.guanxian.platform.ai.impact.PolicyImpactPage;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@RequestMapping("/api/v1/policy-impact-analyses")
public class PolicyImpactController {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private final PolicyImpactAnalysisService service;
    private final ActorScopeResolver actorScopeResolver;

    public PolicyImpactController(
            PolicyImpactAnalysisService service,
            ActorScopeResolver actorScopeResolver) {
        this.service = service;
        this.actorScopeResolver = actorScopeResolver;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
    ResponseEntity<ApiResponse<PolicyImpactAnalysisView>> create(
            @Valid @RequestBody AnalysisRequest request,
            Authentication authentication) {
        return execute(HttpStatus.CREATED, () -> service.create(
                request.policyDocumentId(), request.enterpriseId(), actor(authentication)));
    }

    @PutMapping("/{id}/reanalyze")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
    ResponseEntity<ApiResponse<PolicyImpactAnalysisView>> reanalyze(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        long version = requiredVersion(ifMatch);
        return execute(HttpStatus.OK, () -> service.reanalyze(id, version, actor(authentication)));
    }

    @PutMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
    ResponseEntity<ApiResponse<PolicyImpactAnalysisView>> review(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody ReviewRequest request,
            Authentication authentication) {
        long version = requiredVersion(ifMatch);
        return execute(HttpStatus.OK, () -> service.review(
                id, version, request.approved(), request.comment(), actor(authentication)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ResponseEntity<ApiResponse<PolicyImpactAnalysisView>> get(
            @PathVariable UUID id,
            Authentication authentication) {
        return execute(HttpStatus.OK, () -> service.get(id, actor(authentication)));
    }

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<PolicyImpactPage> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID policyDocumentId,
            @RequestParam(required = false) UUID enterpriseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        try {
            return ApiResponse.ok(service.page(
                    actor(authentication), status, policyDocumentId, enterpriseId, page, size));
        } catch (PolicyImpactException exception) {
            throw apiException(exception);
        }
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<List<PolicyImpactHistoryView>> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        try {
            return ApiResponse.ok(service.history(id, actor(authentication), limit));
        } catch (PolicyImpactException exception) {
            throw apiException(exception);
        }
    }

    private ImpactActor actor(Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        return new ImpactActor(
                actor.userId(), actor.subject(), actor.username(), actor.associationId(), actor.enterpriseId(),
                actor.isSystemAdmin(), actor.isAssociationStaff(), actor.isAssociationReviewer());
    }

    private static ResponseEntity<ApiResponse<PolicyImpactAnalysisView>> execute(
            HttpStatus status,
            ImpactOperation operation) {
        try {
            PolicyImpactAnalysisView result = operation.run();
            return ResponseEntity.status(status)
                    .eTag('"' + Long.toString(result.version()) + '"')
                    .body(ApiResponse.ok(result));
        } catch (PolicyImpactException exception) {
            throw apiException(exception);
        }
    }

    public static long requiredVersion(List<String> ifMatch) {
        if (ifMatch == null || ifMatch.isEmpty()) {
            throw new PreconditionRequiredException("If-Match header is required");
        }
        if (ifMatch.size() != 1) {
            throw invalidIfMatch();
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(ifMatch.getFirst());
        if (!matcher.matches()) {
            throw invalidIfMatch();
        }
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

    private static ApiException apiException(PolicyImpactException exception) {
        return switch (exception.reason()) {
            case NOT_FOUND -> new ApiException("RESOURCE_NOT_FOUND", exception.getMessage(), HttpStatus.NOT_FOUND);
            case FORBIDDEN -> new ApiException("POLICY_IMPACT_SCOPE_VIOLATION", exception.getMessage(), HttpStatus.FORBIDDEN);
            case CONFLICT -> new ApiException("POLICY_IMPACT_ALREADY_EXISTS", exception.getMessage(), HttpStatus.CONFLICT);
            case PRECONDITION_FAILED -> new ApiException(
                    "PRECONDITION_FAILED", exception.getMessage(), HttpStatus.PRECONDITION_FAILED);
            case EVIDENCE_REQUIRED -> new ApiException(
                    "POLICY_IMPACT_EVIDENCE_REQUIRED", exception.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        };
    }

    public record AnalysisRequest(
            @NotNull UUID policyDocumentId,
            @NotNull UUID enterpriseId) {
    }

    public record ReviewRequest(
            boolean approved,
            @Size(max = 1000) String comment) {
    }

    @FunctionalInterface
    private interface ImpactOperation {
        PolicyImpactAnalysisView run();
    }
}
