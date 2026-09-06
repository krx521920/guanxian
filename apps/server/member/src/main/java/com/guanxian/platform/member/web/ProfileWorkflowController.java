package com.guanxian.platform.member.web;

import com.guanxian.platform.member.internal.ProfileWorkflow;
import com.guanxian.platform.member.internal.ProfileWorkflowService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/enterprise-profiles/{id}")
@PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
public class ProfileWorkflowController {
    private final ProfileWorkflowService service;
    private final ActorScopeResolver scopes;
    public ProfileWorkflowController(ProfileWorkflowService service, ActorScopeResolver scopes) {
        this.service=service; this.scopes=scopes;
    }
    public record Save(@NotNull @Valid MemberUpsertRequest content, @Min(0) long baseVersion) { }
    public record Review(@NotNull Boolean approve, @NotBlank @Size(max=1000) String note) { }
    public record Note(@NotBlank @Size(max=1000) String note) { }
    public record Consent(@AssertTrue boolean confirmed) { }
    @GetMapping
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> get(@PathVariable UUID id, Authentication auth) {
        return response(service.get(id,scopes.resolve(auth)));
    }
    @PutMapping("/draft")
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> save(@PathVariable UUID id, @Valid @RequestBody Save body,
            @RequestHeader(value="If-Match",required=false) List<String> match, Authentication auth) {
        return response(service.save(id,MemberController.requiredVersion(match),body.baseVersion(),body.content(),scopes.resolve(auth)));
    }
    @PostMapping("/submit")
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> submit(@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) List<String> match, Authentication auth) {
        return response(service.submit(id,MemberController.requiredVersion(match),scopes.resolve(auth)));
    }
    @PostMapping("/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> review(@PathVariable UUID id,@Valid @RequestBody Review body,
            @RequestHeader(value="If-Match",required=false) List<String> match, Authentication auth) {
        return response(service.review(id,MemberController.requiredVersion(match),body.approve(),body.note(),scopes.resolve(auth)));
    }
    @PostMapping("/consent")
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> consent(@PathVariable UUID id,@Valid @RequestBody Consent body,
            @RequestHeader(value="If-Match",required=false) List<String> match, Authentication auth) {
        return response(service.consent(id,MemberController.requiredVersion(match),scopes.resolve(auth)));
    }
    @PostMapping("/publish")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> publish(@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) List<String> match, Authentication auth) {
        return response(service.publish(id,MemberController.requiredVersion(match),scopes.resolve(auth)));
    }
    @PostMapping("/withdraw")
    ResponseEntity<ApiResponse<ProfileWorkflow.View>> withdraw(@PathVariable UUID id,@Valid @RequestBody Note body,
            @RequestHeader(value="If-Match",required=false) List<String> match, Authentication auth) {
        return response(service.withdraw(id,MemberController.requiredVersion(match),body.note(),scopes.resolve(auth)));
    }
    private static ResponseEntity<ApiResponse<ProfileWorkflow.View>> response(ProfileWorkflow.View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+view.version()+"\"").body(ApiResponse.ok(view));
    }
}
