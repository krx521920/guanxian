package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    ApiResponse<List<EcosystemMatch>> list() {
        return ApiResponse.ok(matchService.demoMatches());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<EcosystemMatch>> requestMatch(
            @Valid @RequestBody MatchRequest request, Authentication authentication) {
        return ApiResponse.ok(matchService.match(request, actorScopeResolver.resolve(authentication)));
    }
}
