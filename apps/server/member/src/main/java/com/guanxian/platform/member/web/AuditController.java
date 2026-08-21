package com.guanxian.platform.member.web;

import com.guanxian.platform.member.internal.AuditRecord;
import com.guanxian.platform.member.internal.AuditTrail;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
class AuditController {
    private final AuditTrail auditTrail;
    private final ActorScopeResolver actorScopeResolver;

    AuditController(AuditTrail auditTrail, ActorScopeResolver actorScopeResolver) {
        this.auditTrail = auditTrail;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    ApiResponse<List<AuditRecord>> list(
            @RequestParam(required = false) UUID enterpriseId,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return ApiResponse.ok(auditTrail.findVisible(
                actorScopeResolver.resolve(authentication), enterpriseId, boundedLimit));
    }
}
