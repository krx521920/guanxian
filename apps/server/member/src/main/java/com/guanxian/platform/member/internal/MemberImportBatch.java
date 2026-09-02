package com.guanxian.platform.member.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record MemberImportBatch(
        UUID id,
        UUID associationId,
        String originalFilename,
        String templateVersion,
        String sourceSha256,
        String submittedUnit,
        UUID submittedEnterpriseId,
        String status,
        String createdBySubject,
        Instant createdAt,
        Instant committedAt,
        List<MemberImportRow> rows) {

    MemberImportBatch {
        rows = List.copyOf(rows);
    }

    MemberImportBatch(
            UUID id, UUID associationId, String originalFilename, String status,
            String createdBySubject, Instant createdAt, Instant committedAt, List<MemberImportRow> rows) {
        this(id, associationId, originalFilename, null, null, null, null,
                status, createdBySubject, createdAt, committedAt, rows);
    }

    int validRows() {
        return (int) rows.stream().filter(row -> "VALID".equals(row.status())).count();
    }

    int invalidRows() {
        return (int) rows.stream().filter(row -> "INVALID".equals(row.status())).count();
    }
}
