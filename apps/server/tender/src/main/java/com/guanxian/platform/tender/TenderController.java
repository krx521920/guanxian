package com.guanxian.platform.tender;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenders")
public class TenderController {
    private static final String ASSOCIATION_WRITE =
            "hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')";

    private final TenderService tenderService;
    private final ActorScopeResolver actorScopeResolver;

    public TenderController(TenderService tenderService, ActorScopeResolver actorScopeResolver) {
        this.tenderService = tenderService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<TenderPage> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(tenderService.page(
                actor(authentication), query, category, region, status, page, size));
    }

    @PostMapping
    @PreAuthorize(ASSOCIATION_WRITE)
    ResponseEntity<ApiResponse<TenderView>> create(
            @Valid @RequestBody TenderUpsertRequest request, Authentication authentication) {
        return response(HttpStatus.CREATED, tenderService.create(request, actor(authentication)));
    }

    @PostMapping("/batch")
    @PreAuthorize(ASSOCIATION_WRITE)
    ApiResponse<List<TenderView>> createBatch(
            @Valid @RequestBody List<TenderUpsertRequest> requests, Authentication authentication) {
        return ApiResponse.ok(tenderService.createBatch(requests, actor(authentication)));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<TenderPage> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ApiResponse.ok(tenderService.mine(actor(authentication), page, size));
    }

    @PostMapping("/{id}/push")
    @PreAuthorize(ASSOCIATION_WRITE)
    ApiResponse<List<TenderPushView>> push(
            @PathVariable UUID id,
            @Valid @RequestBody TenderPushRequest request,
            Authentication authentication) {
        return ApiResponse.ok(tenderService.push(id, request, actor(authentication)));
    }

    @GetMapping("/{id}/pushes")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<List<TenderPushView>> pushes(
            @PathVariable UUID id, Authentication authentication) {
        return ApiResponse.ok(tenderService.pushes(id, actor(authentication)));
    }

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    static ResponseEntity<ApiResponse<TenderView>> response(HttpStatus status, TenderView tender) {
        return ResponseEntity.status(status)
                .eTag('"' + Long.toString(tender.version()) + '"')
                .body(ApiResponse.ok(tender));
    }
}
