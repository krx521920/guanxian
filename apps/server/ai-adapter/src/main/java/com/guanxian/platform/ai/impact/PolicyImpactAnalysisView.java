package com.guanxian.platform.ai.impact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyImpactAnalysisView(
        UUID id,
        UUID policyDocumentId,
        String policyTitle,
        UUID enterpriseId,
        String enterpriseName,
        UUID associationId,
        String impactLevel,
        String summary,
        List<UUID> evidenceChunkIds,
        String status,
        UUID modelExecutionId,
        String reviewedBySubject,
        Instant reviewedAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String analysisMethod) {

    public PolicyImpactAnalysisView {
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
        analysisMethod = "DETERMINISTIC_LEXICAL";
    }
}
