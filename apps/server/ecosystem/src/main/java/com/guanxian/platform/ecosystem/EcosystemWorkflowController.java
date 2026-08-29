package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/matches")
public class EcosystemWorkflowController {
    private final EcosystemWorkflowService service;
    private final ActorScopeResolver actorScopeResolver;

    public EcosystemWorkflowController(
            EcosystemWorkflowService service, ActorScopeResolver actorScopeResolver) {
        this.service = service;
        this.actorScopeResolver = actorScopeResolver;
    }

    @PostMapping("/{matchId}/invitations")
    @PreAuthorize("hasAnyAuthority('ENTERPRISE_WRITE', 'MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<MatchInvitationView>> invite(
            @PathVariable UUID matchId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MatchInvitationRequest request,
            Authentication authentication) {
        return versioned(service.invite(
                matchId, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @GetMapping("/{matchId}/invitations")
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<MatchInvitationView>> invitations(
            @PathVariable UUID matchId, Authentication authentication) {
        return ApiResponse.ok(service.invitations(matchId, actor(authentication)));
    }

    @PostMapping("/invitations/{invitationId}/respond")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<MatchInvitationView>> respond(
            @PathVariable UUID invitationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MatchInvitationResponse response,
            Authentication authentication) {
        return versioned(service.respond(
                invitationId, VersionEtags.requireVersion(ifMatch), response, actor(authentication)));
    }

    @PostMapping("/{matchId}/negotiations")
    @PreAuthorize("hasAnyAuthority('ENTERPRISE_WRITE', 'MEMBER_REVIEW')")
    ApiResponse<NegotiationView> addNegotiation(
            @PathVariable UUID matchId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody NegotiationRequest request,
            Authentication authentication) {
        return ApiResponse.ok(service.addNegotiation(
                matchId, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @GetMapping("/{matchId}/negotiations")
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<NegotiationView>> negotiations(
            @PathVariable UUID matchId, Authentication authentication) {
        return ApiResponse.ok(service.negotiations(matchId, actor(authentication)));
    }

    @PostMapping("/{matchId}/feedback")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ApiResponse<MatchFeedbackView> feedback(
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchFeedbackRequest request,
            Authentication authentication) {
        return ApiResponse.ok(service.feedback(matchId, request, actor(authentication)));
    }

    @GetMapping("/{matchId}/feedback")
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<MatchFeedbackView>> feedback(
            @PathVariable UUID matchId, Authentication authentication) {
        return ApiResponse.ok(service.feedback(matchId, actor(authentication)));
    }

    @PostMapping("/{matchId}/outcomes")
    @PreAuthorize("hasAnyAuthority('ENTERPRISE_WRITE', 'MEMBER_REVIEW')")
    ApiResponse<OutcomeArchiveView> archive(
            @PathVariable UUID matchId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody OutcomeArchiveRequest request,
            Authentication authentication) {
        return ApiResponse.ok(service.archive(
                matchId, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    @GetMapping("/{matchId}/outcomes")
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<OutcomeArchiveView>> outcomes(
            @PathVariable UUID matchId, Authentication authentication) {
        return ApiResponse.ok(service.outcomes(matchId, actor(authentication)));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    private static ResponseEntity<ApiResponse<MatchInvitationView>> versioned(
            MatchInvitationView value) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, VersionEtags.format(value.version()))
                .body(ApiResponse.ok(value));
    }
}
