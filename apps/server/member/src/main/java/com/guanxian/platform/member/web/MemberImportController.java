package com.guanxian.platform.member.web;

import com.guanxian.platform.member.internal.MemberImportService;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/members")
class MemberImportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final MemberImportService importService;
    private final ActorScopeResolver actorScopeResolver;

    MemberImportController(MemberImportService importService, ActorScopeResolver actorScopeResolver) {
        this.importService = importService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping("/import-template")
    @PreAuthorize("hasAuthority('MEMBER_IMPORT')")
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("会员企业调查表.xlsx", StandardCharsets.UTF_8).build().toString())
                .body(importService.template());
    }

    @PostMapping(path = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('MEMBER_IMPORT')")
    ApiResponse<MemberImportPreview> preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) UUID associationId,
            Authentication authentication) {
        if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(
                    "INVALID_MEMBER_IMPORT", "file is empty or exceeds 5 MiB", HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.ok(importService.preview(
                file.getOriginalFilename(), read(file), associationId,
                actorScopeResolver.resolve(authentication)));
    }

    @GetMapping("/imports/{batchId}")
    @PreAuthorize("hasAuthority('MEMBER_IMPORT')")
    ApiResponse<MemberImportPreview> preview(
            @PathVariable UUID batchId, Authentication authentication) {
        return ApiResponse.ok(importService.get(batchId, actorScopeResolver.resolve(authentication)));
    }

    @GetMapping("/{enterpriseId}/provenance")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<MemberDataProvenanceView> provenance(
            @PathVariable UUID enterpriseId, Authentication authentication) {
        return ApiResponse.ok(importService.provenance(
                enterpriseId, actorScopeResolver.resolve(authentication)));
    }

    @PostMapping("/imports/{batchId}/commit")
    @PreAuthorize("hasAuthority('MEMBER_IMPORT')")
    ApiResponse<MemberImportCommitResult> commit(
            @PathVariable UUID batchId, Authentication authentication) {
        return ApiResponse.ok(importService.commit(batchId, actorScopeResolver.resolve(authentication)));
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(
                    "INVALID_MEMBER_IMPORT", "file could not be read", HttpStatus.BAD_REQUEST);
        }
    }
}
