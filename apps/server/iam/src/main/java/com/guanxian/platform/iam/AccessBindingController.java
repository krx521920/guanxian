package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    ApiResponse<List<AccessBindingView>> list() {
        return ApiResponse.ok(service.findAll());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('ACCESS_BINDING_WRITE')")
    ApiResponse<AccessBindingView> upsert(@Valid @RequestBody AccessBindingRequest request, Authentication auth) {
        return ApiResponse.ok(service.upsert(request, actorScopeResolver.resolve(auth)));
    }
}
