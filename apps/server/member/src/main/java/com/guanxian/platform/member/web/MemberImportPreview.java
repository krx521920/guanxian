package com.guanxian.platform.member.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberImportPreview(
        UUID batchId,
        String filename,
        String templateVersion,
        String sourceSha256,
        String submittedUnit,
        UUID submittedEnterpriseId,
        String status,
        int totalRows,
        int validRows,
        int invalidRows,
        Instant createdAt,
        List<MemberImportRowView> rows) {
}
