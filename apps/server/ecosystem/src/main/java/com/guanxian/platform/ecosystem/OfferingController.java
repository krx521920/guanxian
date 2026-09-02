package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/offerings")
public class OfferingController {
    private final EcosystemCatalogService service;
    private final ActorScopeResolver actorScopeResolver;

    public OfferingController(EcosystemCatalogService service, ActorScopeResolver actorScopeResolver) {
        this.service = service;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<EcosystemPage<OfferingView>> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.offerings(actor(authentication), query, includeDeleted, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ResponseEntity<ApiResponse<OfferingView>> detail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        return versioned(service.offering(id, actor(authentication), includeDeleted));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> create(
            @Valid @RequestBody OfferingUpsertRequest request,
            Authentication authentication) {
        return versioned(service.createOffering(request, actor(authentication)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody OfferingUpsertRequest request,
            Authentication authentication) {
        return versioned(service.updateOffering(
                id, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> submit(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(service.submitOffering(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<OfferingView>> review(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ReviewDecisionRequest request,
            Authentication authentication) {
        return versioned(service.reviewOffering(
                id, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> disable(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(service.disableOffering(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> enable(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(service.enableOffering(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> delete(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(service.deleteOffering(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<OfferingView>> restore(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(service.restoreOffering(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    private static ResponseEntity<ApiResponse<OfferingView>> versioned(OfferingView value) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, VersionEtags.format(value.version()))
                .body(ApiResponse.ok(value));
    }
}
