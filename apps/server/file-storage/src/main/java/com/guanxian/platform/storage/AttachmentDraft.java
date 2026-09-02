package com.guanxian.platform.storage;

import java.util.UUID;

record AttachmentDraft(
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
        String uploadedBySubject) {
}
