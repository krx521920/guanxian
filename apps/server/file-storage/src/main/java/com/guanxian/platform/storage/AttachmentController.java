package com.guanxian.platform.storage;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
    private static final Pattern STRONG_VERSION_ETAG = Pattern.compile("\\"(0|[1-9][0-9]*)\\"");

    private final AttachmentService service;
    private final ActorScopeResolver actorScopeResolver;

    public AttachmentController(AttachmentService service, ActorScopeResolver actorScopeResolver) {
        this.service = service;
        this.actorScopeResolver = actorScopeResolver;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<AttachmentView>> upload(
            @RequestParam(required = false) UUID associationId,
            @RequestParam(required = false) UUID enterpriseId,
            @RequestParam(defaultValue = "PRIVATE") String visibility,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        return response(HttpStatus.CREATED,
                service.upload(actor(authentication), associationId, enterpriseId, visibility, file));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ApiResponse<AttachmentPage> page(
            @RequestParam(required = false) UUID enterpriseId,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        ActorScope actor = actor(authentication);
        requireDeletedAccess(actor, includeDeleted);
        return ApiResponse.ok(service.page(actor, enterpriseId, includeDeleted, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ResponseEntity<ApiResponse<AttachmentView>> get(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            Authentication authentication) {
        ActorScope actor = actor(authentication);
        requireDeletedAccess(actor, includeDeleted);
        return response(HttpStatus.OK, service.get(id, actor, includeDeleted));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('MEMBER_READ')")
    ResponseEntity<ByteArrayResource> download(
            @PathVariable UUID id,
            Authentication authentication) {
        AttachmentService.AttachmentDownload download = service.download(id, actor(authentication));
        AttachmentView metadata = download.metadata();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.mediaType()))
                .contentLength(metadata.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(metadata.originalFilename(), StandardCharsets.UTF_8)
                                .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(download.content()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<AttachmentView>> delete(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK,
                service.delete(id, requiredVersion(ifMatch), actor(authentication)));
    }

    @PutMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('ENTERPRISE_WRITE')")
    ResponseEntity<ApiResponse<AttachmentView>> restore(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            Authentication authentication) {
        return response(HttpStatus.OK,
                service.restore(id, requiredVersion(ifMatch), actor(authentication)));
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

    private ActorScope actor(Authentication authentication) {
        return actorScopeResolver.resolve(authentication);
    }

    private static void requireDeletedAccess(ActorScope actor, boolean includeDeleted) {
        if (includeDeleted && !(actor.isSystemAdmin() || actor.isAssociationStaff() || actor.isEnterpriseAdmin())) {
            throw new ApiException("DELETED_FILE_ACCESS_FORBIDDEN",
                    "deleted attachments are only visible to administrators", HttpStatus.FORBIDDEN);
        }
    }

    private static ResponseEntity<ApiResponse<AttachmentView>> response(
            HttpStatus status, AttachmentView view) {
        return ResponseEntity.status(status)
                .eTag('"' + Long.toString(view.version()) + '"')
                .body(ApiResponse.ok(view));
    }

    private static ApiException invalidIfMatch() {
        return new ApiException("INVALID_IF_MATCH",
                "If-Match must be one strong version ETag", HttpStatus.BAD_REQUEST);
    }
}
