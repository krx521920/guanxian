package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import static com.guanxian.platform.iam.EnterpriseInvitations.*;

@RestController
@RequestMapping("/api/v1/my-enterprise/team")
@ConditionalOnProperty(name="guanxian.security.mode",havingValue="jwt",matchIfMissing=true)
@PreAuthorize("hasRole('ENTERPRISE_ADMIN')")
class EnterpriseTeamController {
    private final EnterpriseTeamService team;
    private final EnterpriseInvitationService invitations;
    private final ActorScopeResolver scopes;
    record Invite(@NotBlank @Size(max=100) String username) { }
    record Disable(@NotBlank @Size(max=1000) String note) { }
    EnterpriseTeamController(EnterpriseTeamService team, EnterpriseInvitationService invitations, ActorScopeResolver scopes) {
        this.team=team;this.invitations=invitations;this.scopes=scopes;
    }
    @GetMapping("/members")
    ResponseEntity<ApiResponse<EnterpriseTeamService.Page>> members(@RequestParam(defaultValue="0") int page,Authentication auth) {
        return EnterpriseInvitationController.response(team.members(scopes.resolve(auth),page));
    }
    @GetMapping("/invitations")
    ResponseEntity<ApiResponse<Page>> invitations(@RequestParam(defaultValue="0") int page,Authentication auth) {
        return EnterpriseInvitationController.response(invitations.teamInvitations(scopes.resolve(auth),page));
    }
    @PostMapping("/invitations")
    ResponseEntity<ApiResponse<Issued>> invite(@Valid @RequestBody Invite request,Authentication auth) {
        return EnterpriseInvitationController.response(invitations.createMember(request.username(),scopes.resolve(auth)));
    }
    @PutMapping("/invitations/{id}/revoke")
    ResponseEntity<ApiResponse<View>> revoke(@PathVariable UUID id,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) List<String> version,Authentication auth) {
        return EnterpriseInvitationController.response(invitations.revokeMember(id,VersionEtags.requiredVersion(version),scopes.resolve(auth)));
    }
    @PutMapping("/members/{id}/disable")
    ResponseEntity<ApiResponse<Void>> disable(@PathVariable UUID id,@Valid @RequestBody Disable request,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) List<String> version,Authentication auth) {
        team.disable(id,VersionEtags.requiredVersion(version),request.note(),scopes.resolve(auth));
        return EnterpriseInvitationController.response(null);
    }
}
