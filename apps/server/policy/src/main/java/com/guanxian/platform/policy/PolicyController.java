package com.guanxian.platform.policy;

import com.guanxian.platform.shared.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {
    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_READ')")
    ApiResponse<List<PolicyView>> list(@RequestParam(required = false) String q) {
        return ApiResponse.ok(policyService.findAll(q));
    }
}
