package com.guanxian.platform.policy;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/policies")
public class PolicyController {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private final PolicyService policyService;
    private final ActorScopeResolver actorScopeResolver;

    public PolicyController(PolicyService policyService, ActorScopeResolver actorScopeResolver) {
        this.policyService = policyService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<List<PolicyView>> list(
            @RequestParam(required = false) String q, Authentication authentication) {
        return ApiResponse.ok(policyService.findAll(q, actor(authentication)));
    }

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<PolicyPage> page(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(policyService.page(
                actor(authentication), q, level, includeDeleted, page, size));
    }

    @GetMapping("/levels")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<List<String>> levels(Authentication authentication) {
        return ApiResponse.ok(policyService.levels(actor(authentication)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ResponseEntity<ApiResponse<PolicyView>> get(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        return response(HttpStatus.OK, policyService.get(id, actor(authentication), includeDeleted));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<PolicyView>> create(
            @Valid @RequestBody PolicyUpsertRequest request, Authentication authentication) {
        return response(HttpStatus.CREATED, policyService.create(request, actor(authentication)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<PolicyView>> update(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody PolicyUpsertRequest request,
            Authentication authentication) {
        return response(HttpStatus.OK,
                policyService.update(id, requiredVersion(ifMatch), request, actor(authentication)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<PolicyView>> submit(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK,
                policyService.submit(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @PutMapping("/{id}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<PolicyView>> review(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody PolicyReviewRequest request,
            Authentication authentication) {
        return response(HttpStatus.OK,
                policyService.review(id, requiredVersion(ifMatch), request, actor(authentication)));
    }

    @PutMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<PolicyView>> disable(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK,
                policyService.disable(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<PolicyView>> delete(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK,
                policyService.delete(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<PolicyView>> restore(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK,
                policyService.restore(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<List<PolicyHistoryView>> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        return ApiResponse.ok(policyService.history(id, actor(authentication), limit));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    static ResponseEntity<ApiResponse<PolicyView>> response(HttpStatus status, PolicyView policy) {
        return ResponseEntity.status(status)
                .eTag('"' + Long.toString(policy.version()) + '"')
                .body(ApiResponse.ok(policy));
    }

    static long requiredVersion(List<String> ifMatch) {
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
        return new ApiException("INVALID_IF_MATCH", "If-Match must be one strong version ETag",
                HttpStatus.BAD_REQUEST);
    }
}
