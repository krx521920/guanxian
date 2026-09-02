package com.guanxian.platform.member.web;

import com.guanxian.platform.member.internal.AuditRecord;
import com.guanxian.platform.member.internal.AuditPage;
import com.guanxian.platform.member.internal.AuditTrail;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.http.HttpStatus;
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

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    ApiResponse<AuditPage> page(
            @RequestParam(required = false) UUID enterpriseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long snapshotId,
            Authentication authentication) {
        if (page < 0 || page > AuditTrail.MAX_PAGE) {
            throw new ApiException(
                    "INVALID_AUDIT_PAGE",
                    "page must be between 0 and " + AuditTrail.MAX_PAGE,
                    HttpStatus.BAD_REQUEST);
        }
        if (size < 1 || size > 500) {
            throw new ApiException(
                    "INVALID_AUDIT_PAGE_SIZE",
                    "size must be between 1 and 500",
                    HttpStatus.BAD_REQUEST);
        }
        if (snapshotId != null && snapshotId < 0) {
            throw new ApiException(
                    "INVALID_AUDIT_SNAPSHOT",
                    "snapshotId must be greater than or equal to zero",
                    HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.ok(auditTrail.pageVisible(
                actorScopeResolver.resolve(authentication), enterpriseId, page, size, snapshotId));
    }
}
