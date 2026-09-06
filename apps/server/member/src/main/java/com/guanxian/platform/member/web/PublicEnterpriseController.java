package com.guanxian.platform.member.web;

import com.guanxian.platform.member.internal.ProfileWorkflow.PublicProfile;
import com.guanxian.platform.member.internal.ProfileWorkflowService;
import com.guanxian.platform.shared.api.ApiResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/enterprises")
public class PublicEnterpriseController {
    private final ProfileWorkflowService service;
    public PublicEnterpriseController(ProfileWorkflowService service) { this.service=service; }
    @GetMapping
    ResponseEntity<ApiResponse<List<PublicProfile>>> list(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.publicPage(q,page)));
    }
    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<PublicProfile>> detail(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.publicDetail(id)));
    }
}
