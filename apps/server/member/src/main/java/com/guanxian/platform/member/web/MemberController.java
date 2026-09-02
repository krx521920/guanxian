package com.guanxian.platform.member.web;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/v1/members", "/api/v1/enterprises"})
public class MemberController {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\\\"(0|[1-9][0-9]*)\\\"");

    private final MemberService memberService;
    private final ActorScopeResolver actorScopeResolver;

    public MemberController(MemberService memberService, ActorScopeResolver actorScopeResolver) {
        this.memberService = memberService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<List<MemberListItem>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        return ApiResponse.ok(memberService.findAll(q, null, includeDeleted, actor).stream()
                .map(member -> MemberListItem.from(member, actor, memberService)).toList());
    }

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<MemberPage> page(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<com.guanxian.platform.member.api.MemberProfile> visible = memberService.findAll(q, status, includeDeleted, actor);
        long offset = (long) safePage * safeSize;
        List<com.guanxian.platform.member.api.MemberProfile> pageMembers = offset >= visible.size()
                ? List.of()
                : visible.subList(
                        Math.toIntExact(offset),
                        Math.toIntExact(Math.min((long) visible.size(), offset + safeSize)));
        List<MemberListItem> items = pageMembers.stream()
                .map(member -> MemberListItem.from(member, actor, memberService)).toList();
        return ApiResponse.ok(new MemberPage(items, visible.size(), safePage, safeSize));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ResponseEntity<ApiResponse<MemberProfile>> get(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        return response(HttpStatus.OK, memberService.get(
                id, actorScopeResolver.resolve(authentication), includeDeleted));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN', 'ASSOCIATION_OPERATOR')")
    ResponseEntity<ApiResponse<MemberProfile>> create(
            @Valid @RequestBody MemberUpsertRequest request, Authentication authentication) {
        return response(HttpStatus.CREATED, memberService.create(request, actorScopeResolver.resolve(authentication)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<MemberProfile>> update(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody MemberUpsertRequest request,
            Authentication authentication) {
        return response(HttpStatus.OK, memberService.update(
                id, requiredVersion(ifMatch), request, actorScopeResolver.resolve(authentication)));
    }

    @PutMapping("/{id}/review")
    @PreAuthorize("hasAuthority('MEMBER_REVIEW')")
    ResponseEntity<ApiResponse<MemberProfile>> review(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody MemberReviewRequest request,
            Authentication authentication) {
        return response(HttpStatus.OK, memberService.review(
                id, requiredVersion(ifMatch), request, actorScopeResolver.resolve(authentication)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
    ResponseEntity<ApiResponse<Map<String, Object>>> delete(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        MemberProfile deleted = memberService.delete(
                id, requiredVersion(ifMatch), actorScopeResolver.resolve(authentication));
        return ResponseEntity.ok()
                .eTag('"' + Long.toString(deleted.version()) + '"')
                .body(ApiResponse.ok(Map.of(
                        "deleted", true, "id", deleted.id(), "version", deleted.version())));
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
    ResponseEntity<ApiResponse<MemberProfile>> restore(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK, memberService.restore(
                id, requiredVersion(ifMatch), actorScopeResolver.resolve(authentication)));
    }

    static ResponseEntity<ApiResponse<MemberProfile>> response(HttpStatus status, MemberProfile member) {
        return ResponseEntity.status(status)
                .eTag('"' + Long.toString(member.version()) + '"')
                .body(ApiResponse.ok(member));
    }

    static long requiredVersion(List<String> ifMatch) {
        if (ifMatch == null || ifMatch.isEmpty()) {
            throw new PreconditionRequiredException("If-Match header is required");
        }
        if (ifMatch.size() != 1) {
            throw invalidIfMatch();
        }
        Matcher matcher = STRONG_VERSION_ETAG.matcher(ifMatch.getFirst());
        if (!matcher.matches()) {
            throw invalidIfMatch();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalidIfMatch();
        }
    }

    private static ApiException invalidIfMatch() {
        return new ApiException(
                "INVALID_IF_MATCH", "If-Match must be one strong version ETag", HttpStatus.BAD_REQUEST);
    }
}
