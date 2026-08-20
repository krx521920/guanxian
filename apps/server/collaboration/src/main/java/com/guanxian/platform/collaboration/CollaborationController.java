package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.api.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/collaborations")
public class CollaborationController {
    private final CollaborationService collaborationService;

    public CollaborationController(CollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('COLLABORATION_READ')")
    ApiResponse<List<CollaborationView>> list() {
        return ApiResponse.ok(collaborationService.findAll());
    }
}
