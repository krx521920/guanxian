package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/access-bindings")
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class AccessBindingController {
    private final AccessBindingService service;
    private final ActorScopeResolver actorScopeResolver;

    AccessBindingController(AccessBindingService service, ActorScopeResolver actorScopeResolver) {
        this.service = service;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<List<AccessBindingView>> list(Authentication authentication) {
        return ApiResponse.ok(service.findAll(actorScopeResolver.resolve(authentication)));
    }

    @GetMapping("/page")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<AccessBindingPage> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(service.page(actorScopeResolver.resolve(authentication), page, size));
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
    ResponseEntity<ApiResponse<AccessBindingView>> upsert(
            @Valid @RequestBody AccessBindingRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication auth) {
        return versioned(service.upsert(request, optionalVersion(ifMatch), actorScopeResolver.resolve(auth)));
    }

    @PutMapping("/{id}/disable")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
    ResponseEntity<ApiResponse<AccessBindingView>> disable(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication auth) {
        return versioned(service.disable(
                id, VersionEtags.requiredVersion(ifMatch), actorScopeResolver.resolve(auth)));
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
    ResponseEntity<ApiResponse<AccessBindingView>> restore(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication auth) {
        return versioned(service.restore(
                id, VersionEtags.requiredVersion(ifMatch), actorScopeResolver.resolve(auth)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
    ResponseEntity<ApiResponse<AccessBindingView>> unbind(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication auth) {
        return versioned(service.unbind(
                id, VersionEtags.requiredVersion(ifMatch), actorScopeResolver.resolve(auth)));
    }

    private static Long optionalVersion(List<String> ifMatch) {
        return ifMatch == null || ifMatch.isEmpty() ? null : VersionEtags.requiredVersion(ifMatch);
    }

    private static ResponseEntity<ApiResponse<AccessBindingView>> versioned(AccessBindingView value) {
        return ResponseEntity.ok()
                .eTag("\"" + value.version() + "\"")
                .body(ApiResponse.ok(value));
    }
}
