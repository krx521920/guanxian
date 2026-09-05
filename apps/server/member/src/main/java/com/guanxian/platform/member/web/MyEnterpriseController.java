package com.guanxian.platform.member.web;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/my-enterprise")
public class MyEnterpriseController {
    private final MemberService members;
    private final ActorScopeResolver scopes;

    public MyEnterpriseController(MemberService members, ActorScopeResolver scopes) {
        this.members = members;
        this.scopes = scopes;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ENTERPRISE_ADMIN', 'ENTERPRISE_MEMBER') and hasAuthority('MEMBER_READ')")
    ResponseEntity<ApiResponse<View>> get(Authentication authentication) {
        ActorScope actor = ownScope(authentication);
        return response(members.get(actor.enterpriseId(), actor), actor.isEnterpriseAdmin());
    }

    @PutMapping
    @PreAuthorize("hasRole('ENTERPRISE_ADMIN') and hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<View>> update(
            @Valid @RequestBody MemberUpsertRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        ActorScope actor = ownScope(authentication);
        if (!actor.isEnterpriseAdmin()) throw denied();
        // The target is never accepted from a path, payload or selected administrator context.
        MemberController.requiredVersion(ifMatch);
        throw new ForbiddenException("PROFILE_DRAFT_REQUIRED", "请通过资料草稿提交审核，正式资料不能直接覆盖");
    }

    private ActorScope ownScope(Authentication authentication) {
        ActorScope actor = scopes.resolve(authentication);
        if (actor == null || actor.enterpriseId() == null || actor.associationId() == null
                || actor.isSystemAdmin() || actor.isAssociationStaff()
                || (!actor.hasRole("ENTERPRISE_ADMIN") && !actor.hasRole("ENTERPRISE_MEMBER"))) throw denied();
        return actor;
    }

    private static ResponseEntity<ApiResponse<View>> response(MemberProfile member, boolean canEdit) {
        MemberProfile profile = canEdit ? member : new MemberProfile(
                member.id(), member.associationId(), member.name(), null, member.category(), null, null, null, null,
                member.introduction(), member.capabilities(), member.products(), member.services(),
                member.applicationScenarios(), member.cooperationNeeds(), member.visibility(), member.status(),
                member.version(), member.createdAt(), member.updatedAt(), null, null, null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + member.version() + "\"")
                .body(ApiResponse.ok(new View(profile, canEdit)));
    }

    private static ForbiddenException denied() {
        return new ForbiddenException("ENTERPRISE_BINDING_REQUIRED", "已绑定的企业账号才能访问我的企业");
    }

    public record View(MemberProfile profile, boolean canEdit) { }
}
