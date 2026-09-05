package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.guanxian.platform.iam.EnterpriseInvitations.*;

/** Valid JWT required, but no business binding required to request that binding. */
@RestController
@RequestMapping("/api/v1/onboarding")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class EnterpriseOnboardingController {
    private final EnterpriseInvitationService service;
    EnterpriseOnboardingController(EnterpriseInvitationService service) { this.service = service; }
    @GetMapping("/session")
    ResponseEntity<ApiResponse<Identity>> session(Authentication auth) {
        return EnterpriseInvitationController.response(service.identity(auth));
    }
    @GetMapping("/invitations")
    ResponseEntity<ApiResponse<List<View>>> mine(Authentication auth) {
        return EnterpriseInvitationController.response(service.mine(auth));
    }
    @PostMapping("/preview")
    ResponseEntity<ApiResponse<View>> preview(@Valid @RequestBody Token request, Authentication auth) {
        return EnterpriseInvitationController.response(service.preview(request.token(), auth));
    }
    @PostMapping("/claim")
    ResponseEntity<ApiResponse<View>> claim(@Valid @RequestBody Claim request, Authentication auth) {
        return EnterpriseInvitationController.response(service.claim(request, auth));
    }
}
