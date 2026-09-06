package com.guanxian.platform.member.web;
import com.guanxian.platform.member.internal.ProfileWorkflowService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ProfileReviewQueueController {
    private final ProfileWorkflowService service; private final ActorScopeResolver scopes;
    public ProfileReviewQueueController(ProfileWorkflowService service,ActorScopeResolver scopes){this.service=service;this.scopes=scopes;}
    @GetMapping("/api/v1/enterprise-profile-reviews")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','ASSOCIATION_ADMIN','ASSOCIATION_OPERATOR') and hasAuthority('MEMBER_READ')")
    ResponseEntity<ApiResponse<List<ProfileWorkflowService.Pending>>> pending(@RequestParam(defaultValue="0") int page,Authentication auth){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.pending(scopes.resolve(auth),page)));
    }
}
