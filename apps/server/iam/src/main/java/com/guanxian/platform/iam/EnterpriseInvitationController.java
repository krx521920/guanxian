package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import static com.guanxian.platform.iam.EnterpriseInvitations.*;

@RestController
@RequestMapping("/api/v1/enterprise-invitations")
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
class EnterpriseInvitationController {
    private final EnterpriseInvitationService service;
    private final ActorScopeResolver scopes;
    EnterpriseInvitationController(EnterpriseInvitationService service, ActorScopeResolver scopes) {
        this.service = service; this.scopes = scopes;
    }
    @GetMapping
    ResponseEntity<ApiResponse<Page>> list(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, Authentication auth) {
        return response(service.list(scopes.resolve(auth), page, size));
    }
    @PostMapping
    ResponseEntity<ApiResponse<Issued>> create(@Valid @RequestBody Create request, Authentication auth) {
        return response(service.create(request, scopes.resolve(auth)));
    }
    @PutMapping("/{id}/review")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
    ResponseEntity<ApiResponse<View>> review(@PathVariable UUID id, @Valid @RequestBody Review request,
            @RequestHeader(value=HttpHeaders.IF_MATCH, required=false) List<String> version, Authentication auth) {
        return versioned(service.review(id, VersionEtags.requiredVersion(version), request, scopes.resolve(auth)));
    }
    @PutMapping("/{id}/revoke")
    ResponseEntity<ApiResponse<View>> revoke(@PathVariable UUID id,
            @RequestHeader(value=HttpHeaders.IF_MATCH, required=false) List<String> version, Authentication auth) {
        return versioned(service.revoke(id, VersionEtags.requiredVersion(version), scopes.resolve(auth)));
    }
    private static ResponseEntity<ApiResponse<View>> versioned(View value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+value.version()+"\"").body(ApiResponse.ok(value));
    }
    static <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
