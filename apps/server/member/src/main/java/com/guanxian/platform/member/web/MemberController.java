package com.guanxian.platform.member.web;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<List<MemberListItem>> list(@RequestParam(required = false) String q) {
        return ApiResponse.ok(memberService.findAll(q).stream().map(MemberListItem::from).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ResponseEntity<ApiResponse<MemberProfile>> get(@PathVariable UUID id) {
        return response(HttpStatus.OK, memberService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<MemberProfile>> create(@Valid @RequestBody MemberUpsertRequest request) {
        return response(HttpStatus.CREATED, memberService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<MemberProfile>> update(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @Valid @RequestBody MemberUpsertRequest request) {
        return response(HttpStatus.OK, memberService.update(id, requiredVersion(ifMatch), request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'ASSOCIATION_ADMIN')")
    ApiResponse<Map<String, Object>> delete(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch) {
        MemberProfile deleted = memberService.delete(id, requiredVersion(ifMatch));
        return ApiResponse.ok(Map.of("deleted", true, "id", deleted.id()));
    }

    private static ResponseEntity<ApiResponse<MemberProfile>> response(HttpStatus status, MemberProfile member) {
        return ResponseEntity.status(status)
                .eTag('"' + Long.toString(member.version()) + '"')
                .body(ApiResponse.ok(member));
    }

    private static long requiredVersion(List<String> ifMatch) {
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
