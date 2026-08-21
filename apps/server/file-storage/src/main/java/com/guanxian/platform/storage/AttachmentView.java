package com.guanxian.platform.storage;

import java.time.Instant;
import java.util.UUID;

public record AttachmentView(
        UUID id,
        UUID associationId,
        UUID enterpriseId,
        String bucketName,
        String objectKey,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String scanStatus,
        String visibility,
        String status,
        long version,
        String uploadedBySubject,
        Instant uploadedAt,
        Instant updatedAt,
        Instant deletedAt) {
}
