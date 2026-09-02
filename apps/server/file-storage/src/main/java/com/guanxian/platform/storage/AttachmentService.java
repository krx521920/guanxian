package com.guanxian.platform.storage;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {
    static final String CONTENT_VALIDATED = "VALIDATED";
    private static final Map<String, Set<String>> MEDIA_EXTENSIONS = Map.of(
            "application/pdf", Set.of("pdf"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx"),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx"),
            "image/jpeg", Set.of("jpg", "jpeg"),
            "image/png", Set.of("png"),
            "text/plain", Set.of("txt"),
            "text/csv", Set.of("csv"));

    private final AttachmentMetadataStore metadata;
    private final ObjectStorage objects;
    private final AttachmentRateLimiter rateLimiter;
    private final StorageProperties properties;
    private final AttachmentEnterpriseScope enterpriseScope;
    private final AttachmentContentScanner contentScanner;

    public AttachmentService(
            AttachmentMetadataStore metadata,
            ObjectStorage objects,
            AttachmentRateLimiter rateLimiter,
            StorageProperties properties,
            AttachmentEnterpriseScope enterpriseScope) {
        this(metadata, objects, rateLimiter, properties, enterpriseScope, new ContentValidationOnlyScanner());
    }

    @Autowired
    public AttachmentService(
            AttachmentMetadataStore metadata,
            ObjectStorage objects,
            AttachmentRateLimiter rateLimiter,
            StorageProperties properties,
            AttachmentEnterpriseScope enterpriseScope,
            AttachmentContentScanner contentScanner) {
        this.metadata = metadata;
        this.objects = objects;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.enterpriseScope = enterpriseScope;
        this.contentScanner = contentScanner;
    }

    public AttachmentView upload(
            ActorScope actor,
            UUID requestedAssociationId,
            UUID requestedEnterpriseId,
            String requestedVisibility,
            MultipartFile file) {
        Scope target = writableScope(actor, requestedAssociationId, requestedEnterpriseId);
        rateLimiter.check(actor, "upload");
        ValidatedFile validated = validate(file);
        contentScanner.assertClean(validated.content());
        String visibility = normalizeVisibility(requestedVisibility, target.enterpriseId());
        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        String objectKey = "%s/%s/%04d/%02d/%s".formatted(
                target.associationId(),
                target.enterpriseId() == null ? "association" : target.enterpriseId(),
                today.getYear(),
                today.getMonthValue(),
                id);
        AttachmentDraft draft = new AttachmentDraft(
                id, target.associationId(), target.enterpriseId(), properties.getBucket(), objectKey,
                validated.filename(), validated.mediaType(), validated.content().length,
                sha256(validated.content()), CONTENT_VALIDATED, visibility, actor.subject());
        objects.put(objectKey, validated.mediaType(), validated.content());
        try {
            return metadata.create(draft, actor);
        } catch (RuntimeException exception) {
            try {
                objects.delete(objectKey);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public AttachmentPage page(
            ActorScope actor, UUID enterpriseId, boolean includeDeleted, int page, int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        UUID visibleEnterpriseId = visibleEnterpriseFilter(actor, enterpriseId);
        if (visibleEnterpriseId != null && actor.isEnterpriseAdmin()
                && !visibleEnterpriseId.equals(actor.enterpriseId())) {
            throw new ForbiddenException("ENTERPRISE_SCOPE_VIOLATION",
                    "enterprise administrators may only list their own attachments");
        }
        return new AttachmentPage(
                metadata.listVisible(actor, visibleEnterpriseId, includeDeleted,
                        boundedPage * boundedSize, boundedSize),
                boundedPage,
                boundedSize,
                metadata.countVisible(actor, visibleEnterpriseId, includeDeleted));
    }

    public AttachmentView get(UUID id, ActorScope actor, boolean includeDeleted) {
        return metadata.findVisible(id, actor, includeDeleted)
                .orElseThrow(() -> new NotFoundException("attachment", id));
    }

    public AttachmentDownload download(UUID id, ActorScope actor) {
        AttachmentView view = get(id, actor, false);
        if (!CONTENT_VALIDATED.equals(view.scanStatus())) {
            throw new ApiException(
                    "ATTACHMENT_CONTENT_UNAVAILABLE",
                    "attachment content is unavailable until validation succeeds; legacy pending files must be uploaded again",
                    HttpStatus.CONFLICT);
        }
        byte[] content = objects.get(view.objectKey());
        if (content.length != view.sizeBytes() || content.length > properties.getMaxSizeBytes()) {
            throw new StorageUnavailableException("stored object failed size verification");
        }
        if (!sha256(content).equals(view.sha256())) {
            throw new StorageUnavailableException("stored object failed integrity verification");
        }
        return new AttachmentDownload(view, content);
    }

    public AttachmentView delete(UUID id, long expectedVersion, ActorScope actor) {
        requireSystemWriteContext(actor);
        rateLimiter.check(actor, "delete");
        AttachmentView current = get(id, actor, true);
        requireVersion(current, expectedVersion);
        if (current.deletedAt() != null) {
            throw new ConflictException("attachment is already deleted");
        }
        return metadata.softDelete(id, expectedVersion, actor)
                .orElseThrow(() -> new PreconditionFailedException("attachment changed or is outside writable scope"));
    }

    public AttachmentView restore(UUID id, long expectedVersion, ActorScope actor) {
        requireSystemWriteContext(actor);
        rateLimiter.check(actor, "restore");
        AttachmentView current = get(id, actor, true);
        requireVersion(current, expectedVersion);
        if (current.deletedAt() == null) {
            throw new ConflictException("attachment is not deleted");
        }
        return metadata.restore(id, expectedVersion, actor)
                .orElseThrow(() -> new PreconditionFailedException("attachment changed or is outside writable scope"));
    }

    private Scope writableScope(ActorScope actor, UUID associationId, UUID enterpriseId) {
        if (actor.isSystemAdmin()) {
            requireSystemWriteContext(actor);
            if (associationId != null && !associationId.equals(actor.associationId())) {
                throw new ForbiddenException("ASSOCIATION_SCOPE_VIOLATION",
                        "request association cannot override the selected system context");
            }
            if (enterpriseId != null && !enterpriseId.equals(actor.enterpriseId())) {
                throw new ForbiddenException("ENTERPRISE_SCOPE_VIOLATION",
                        "request enterprise cannot override the selected system context");
            }
            return checkedScope(actor, actor.associationId(), actor.enterpriseId());
        }
        if (actor.associationId() == null) {
            throw new ForbiddenException("ASSOCIATION_SCOPE_REQUIRED", "actor has no association scope");
        }
        if (associationId != null && !actor.associationId().equals(associationId)) {
            throw new ForbiddenException("ASSOCIATION_SCOPE_VIOLATION",
                    "actor cannot upload into another association");
        }
        if (actor.isAssociationStaff()) {
            return checkedScope(actor, actor.associationId(), enterpriseId);
        }
        if (actor.isEnterpriseAdmin() && actor.enterpriseId() != null) {
            if (enterpriseId != null && !actor.enterpriseId().equals(enterpriseId)) {
                throw new ForbiddenException("ENTERPRISE_SCOPE_VIOLATION",
                        "enterprise administrators may only upload for their own enterprise");
            }
            return checkedScope(actor, actor.associationId(), actor.enterpriseId());
        }
        throw new ForbiddenException("ATTACHMENT_WRITE_FORBIDDEN",
                "actor is not allowed to manage attachments");
    }

    private Scope checkedScope(ActorScope actor, UUID associationId, UUID enterpriseId) {
        if (enterpriseId != null && !enterpriseScope.contains(associationId, enterpriseId, actor)) {
            throw new ForbiddenException("ATTACHMENT_SCOPE_VIOLATION",
                    "target enterprise does not belong to the selected association");
        }
        return new Scope(associationId, enterpriseId);
    }

    private static UUID visibleEnterpriseFilter(ActorScope actor, UUID requestedEnterpriseId) {
        if (actor.isSystemAdmin() && actor.enterpriseId() != null) {
            if (requestedEnterpriseId != null && !actor.enterpriseId().equals(requestedEnterpriseId)) {
                throw new ForbiddenException("ENTERPRISE_SCOPE_VIOLATION",
                        "request enterprise cannot override the selected system context");
            }
            return actor.enterpriseId();
        }
        return requestedEnterpriseId;
    }

    private static void requireSystemWriteContext(ActorScope actor) {
        if (actor.isSystemAdmin() && actor.associationId() == null) {
            throw new ForbiddenException("ASSOCIATION_CONTEXT_REQUIRED",
                    "system administrators must select an association before writing attachments");
        }
    }

    private ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("file must not be empty");
        }
        if (properties.getMaxSizeBytes() < 1 || properties.getMaxSizeBytes() > 100L * 1024 * 1024) {
            throw new IllegalStateException("attachment maximum size must be between 1 byte and 100 MiB");
        }
        if (file.getSize() > properties.getMaxSizeBytes()) {
            throw new ApiException("FILE_TOO_LARGE",
                    "file exceeds the configured maximum size", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        String filename = safeFilename(file.getOriginalFilename());
        String mediaType = normalizeMediaType(file.getContentType());
        Set<String> extensions = MEDIA_EXTENSIONS.get(mediaType);
        String extension = extension(filename);
        if (extensions == null || !extensions.contains(extension)) {
            throw invalid("file type and extension are not allowed");
        }
        try {
            byte[] content = file.getBytes();
            if (content.length != file.getSize() || !matchesSignature(mediaType, content)) {
                throw invalid("file content does not match its declared type");
            }
            return new ValidatedFile(filename, mediaType, content);
        } catch (IOException exception) {
            throw new ApiException("FILE_READ_FAILED", "uploaded file could not be read",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static String safeFilename(String raw) {
        String value = raw == null ? "" : Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (value.isBlank() || value.length() > 180 || ".".equals(value) || "..".equals(value)
                || value.contains("/") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("filename is invalid");
        }
        return value;
    }

    private static String normalizeMediaType(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 1 || dot == filename.length() - 1
                ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeVisibility(String raw, UUID enterpriseId) {
        String value = raw == null || raw.isBlank() ? "PRIVATE" : raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PRIVATE", "ASSOCIATION").contains(value)) {
            throw invalid("visibility must be PRIVATE or ASSOCIATION");
        }
        if (enterpriseId == null && "PRIVATE".equals(value)) {
            return "ASSOCIATION";
        }
        return value;
    }

    private static boolean matchesSignature(String mediaType, byte[] content) {
        return switch (mediaType) {
            case "application/pdf" -> startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> startsWith(content,
                    new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> content.length >= 3
                    && content[0] == (byte) 0xff && content[1] == (byte) 0xd8 && content[2] == (byte) 0xff;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    content.length >= 4 && content[0] == 'P' && content[1] == 'K'
                            && content[2] == 3 && content[3] == 4;
            case "text/plain", "text/csv" -> {
                for (byte value : content) {
                    if (value == 0) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false;
        };
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireVersion(AttachmentView view, long expectedVersion) {
        if (view.version() != expectedVersion) {
            throw new PreconditionFailedException("attachment was changed by another request");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException("INVALID_FILE", message, HttpStatus.BAD_REQUEST);
    }

    private record Scope(UUID associationId, UUID enterpriseId) {
    }

    private record ValidatedFile(String filename, String mediaType, byte[] content) {
    }

    public record AttachmentDownload(AttachmentView metadata, byte[] content) {
        public AttachmentDownload {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
