package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/enterprise-accounts/enterprises/{id}")
@ConditionalOnProperty(name="guanxian.security.mode",havingValue="jwt",matchIfMissing=true)
@PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
class ManagedEnterpriseAccountController {
    private final ManagedEnterpriseAccounts service;
    private final ActorScopeResolver scopes;
    ManagedEnterpriseAccountController(ManagedEnterpriseAccounts service,ActorScopeResolver scopes) { this.service=service;this.scopes=scopes; }
    record Create(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=1000) String note,@NotNull @AssertTrue Boolean confirmed) { }
    record Action(@NotBlank @Size(max=1000) String note,@NotNull @AssertTrue Boolean confirmed) { }
    @GetMapping ResponseEntity<ApiResponse<ManagedEnterpriseAccounts.View>> get(@PathVariable UUID id,Authentication auth) {
        return response(service.get(id,scopes.resolve(auth)));
    }
    @PostMapping ResponseEntity<ApiResponse<ManagedEnterpriseAccounts.Result>> create(@PathVariable UUID id,@Valid @RequestBody Create body,
            @RequestHeader(value="If-Match",required=false) List<String> version,Authentication auth) {
        return response(service.create(id,VersionEtags.requiredVersion(version),body.username(),body.note(),scopes.resolve(auth)));
    }
    @PostMapping("/reset-password") ResponseEntity<ApiResponse<ManagedEnterpriseAccounts.Result>> reset(@PathVariable UUID id,@Valid @RequestBody Action body,
            @RequestHeader(value="If-Match",required=false) List<String> version,Authentication auth) {
        return response(service.reset(id,VersionEtags.requiredVersion(version),body.note(),scopes.resolve(auth)));
    }
    @PostMapping("/resume") ResponseEntity<ApiResponse<ManagedEnterpriseAccounts.Result>> resume(@PathVariable UUID id,@Valid @RequestBody Action body,
            @RequestHeader(value="If-Match",required=false) List<String> version,Authentication auth) {
        return response(service.resume(id,VersionEtags.requiredVersion(version),body.note(),scopes.resolve(auth)));
    }
    private static <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(ApiResponse.ok(value));
    }
}
