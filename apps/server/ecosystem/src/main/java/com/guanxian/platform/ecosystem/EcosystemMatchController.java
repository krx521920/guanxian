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
@RequestMapping({"/api/v1/matches", "/api/v1/ecosystem/matches"})
public class EcosystemMatchController {
    private final EcosystemMatchService matchService;
    private final ActorScopeResolver actorScopeResolver;

    public EcosystemMatchController(EcosystemMatchService matchService, ActorScopeResolver actorScopeResolver) {
        this.matchService = matchService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<PersistedMatchView>> list(Authentication authentication) {
        return ApiResponse.ok(matchService.list(actor(authentication)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<EcosystemMatch>> requestMatch(
            @Valid @RequestBody MatchRequest request, Authentication authentication) {
        return ApiResponse.ok(matchService.match(request, actor(authentication)));
    }

    @GetMapping("/demand/{demandId}")
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<PersistedMatchView>> persisted(
            @PathVariable UUID demandId, Authentication authentication) {
        return ApiResponse.ok(matchService.persisted(demandId, actor(authentication)));
    }

    @PostMapping("/demand/{demandId}/generate")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ApiResponse<List<PersistedMatchView>> generate(
            @PathVariable UUID demandId,
            @Valid @RequestBody(required = false) MatchGenerationRequest request,
            Authentication authentication) {
        return ApiResponse.ok(matchService.generate(
                demandId, request == null ? null : request.limit(), actor(authentication)));
    }

    @PostMapping("/{id}/recommend")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<PersistedMatchView>> recommend(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(matchService.recommend(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<PersistedMatchView>> confirm(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            Authentication authentication) {
        return versioned(matchService.confirm(
                id, VersionEtags.requireVersion(ifMatch), actor(authentication)));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<PersistedMatchView>> close(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody MatchCloseRequest request,
            Authentication authentication) {
        return versioned(matchService.close(
                id, VersionEtags.requireVersion(ifMatch), request, actor(authentication)));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    private static ResponseEntity<ApiResponse<PersistedMatchView>> versioned(PersistedMatchView value) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, VersionEtags.format(value.version()))
                .body(ApiResponse.ok(value));
    }
}
