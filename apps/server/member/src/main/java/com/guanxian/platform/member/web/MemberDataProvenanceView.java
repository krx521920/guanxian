package com.guanxian.platform.member.web;

import java.time.Instant;
import java.util.UUID;

public record MemberDataProvenanceView(
        UUID enterpriseId,
        UUID importBatchId,
        int sourceRowNumber,
        String sourceFilename,
        String sourceSha256,
        String templateVersion,
        String submittedUnit,
        UUID submittedEnterpriseId,
        String submittedBySubject,
        Instant submittedAt,
        String reviewedBySubject,
        Instant reviewedAt) {
}
