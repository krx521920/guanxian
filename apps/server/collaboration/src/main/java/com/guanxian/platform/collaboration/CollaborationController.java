package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.api.ApiResponse;
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

@RestController
@RequestMapping("/api/v1/collaborations")
public class CollaborationController {
    private final CollaborationService collaborationService;
    private final ActorScopeResolver actorScopeResolver;

    public CollaborationController(
            CollaborationService collaborationService,
            ActorScopeResolver actorScopeResolver) {
        this.collaborationService = collaborationService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('COLLABORATION_READ')")
    ApiResponse<List<CollaborationView>> list(
            @RequestParam(required = false) String query,
            Authentication authentication) {
        return ApiResponse.ok(collaborationService.page(
                actor(authentication), query, false, 0, 100).items());
    }

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('COLLABORATION_READ')")
    ApiResponse<CollaborationPage<CollaborationView>> page(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String stage,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(collaborationService.page(
                actor(authentication), query, stage, includeDeleted, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('COLLABORATION_READ')")
    ResponseEntity<ApiResponse<CollaborationView>> detail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.get(id, actor(authentication), includeDeleted));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> create(
            @Valid @RequestBody CollaborationUpsertRequest request,
            Authentication authentication) {
        return versioned(
                HttpStatus.CREATED,
                collaborationService.create(request, actor(authentication)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CollaborationUpsertRequest request,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.update(
                        id, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> submit(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.submit(
                        id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<CollaborationView>> review(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CollaborationReviewRequest request,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.review(
                        id, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @PostMapping("/{id}/transition")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> transition(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CollaborationTransitionRequest request,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.advance(
                        id, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> disable(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.disable(
                        id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.delete(
                        id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<CollaborationView>> restore(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(
                HttpStatus.OK,
                collaborationService.restore(
                        id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @GetMapping("/{id}/activities")
    @PreAuthorize("hasAuthority('COLLABORATION_READ')")
    ApiResponse<List<CollaborationActivityView>> activities(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        return ApiResponse.ok(collaborationService.activities(
                id, limit, actor(authentication)));
    }

    @PostMapping("/{id}/activities")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ApiResponse<CollaborationActivityView> addActivity(
            @PathVariable UUID id,
            @Valid @RequestBody CollaborationActivityRequest request,
            Authentication authentication) {
        return ApiResponse.ok(collaborationService.addActivity(
                id, request, actor(authentication)));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('COLLABORATION_READ')")
    ApiResponse<List<CollaborationHistoryView>> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        return ApiResponse.ok(collaborationService.history(
                id, limit, actor(authentication)));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    private static ResponseEntity<ApiResponse<CollaborationView>> versioned(
            HttpStatus status, CollaborationView value) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.ETAG, VersionEtags.format(value.version()))
                .body(ApiResponse.ok(value));
    }
}
