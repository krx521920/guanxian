package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public EcosystemMatchController(EcosystemMatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<EcosystemMatch>> list() {
        return ApiResponse.ok(matchService.demoMatches());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MATCH_REQUEST')")
    ApiResponse<List<EcosystemMatch>> requestMatch(@Valid @RequestBody MatchRequest request) {
        return ApiResponse.ok(matchService.match(request));
    }
}
