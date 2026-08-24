package com.guanxian.platform.ai.impact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyImpactAnalysisStore {
    Optional<AnalysisSource> loadSource(UUID policyDocumentId, UUID enterpriseId);

    Optional<PolicyImpactAnalysisView> find(UUID id);

    Optional<PolicyImpactAnalysisView> findByPair(UUID policyDocumentId, UUID enterpriseId);

    List<PolicyImpactAnalysisView> list(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId, int offset, int limit);

    long count(ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId);

    PolicyImpactAnalysisView create(AnalysisDraft draft);

    Optional<PolicyImpactAnalysisView> reanalyze(UUID id, long expectedVersion, AnalysisDraft draft);

    Optional<PolicyImpactAnalysisView> review(
            UUID id, long expectedVersion, String targetStatus, String reviewerSubject);

    void recordChange(ImpactActor actor, String action, PolicyImpactAnalysisView value, String comment);

    List<PolicyImpactHistoryView> history(UUID id, int limit);

    record AnalysisDraft(
            UUID policyDocumentId,
            String policyTitle,
            UUID enterpriseId,
            String enterpriseName,
            UUID associationId,
            String impactLevel,
            String summary,
            List<UUID> evidenceChunkIds) {
        public AnalysisDraft {
            evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
        }
    }

    record ReadScope(boolean systemAdmin, UUID associationId, UUID enterpriseId, boolean associationStaff) {
    }

    record ImpactActor(
            UUID userId,
            String subject,
            String username,
            UUID associationId,
            UUID enterpriseId,
            boolean systemAdmin,
            boolean associationStaff,
            boolean associationReviewer) {
        public ImpactActor {
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("actor subject is required");
            }
        }

        ReadScope readScope() {
            return new ReadScope(systemAdmin, associationId, enterpriseId, associationStaff);
        }
    }

    record AnalysisSource(
            UUID policyDocumentId,
            String policyTitle,
            UUID enterpriseId,
            String enterpriseName,
            UUID associationId,
            String enterpriseProfile,
            List<SourceChunk> chunks) {
        public AnalysisSource {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    record SourceChunk(UUID id, String content) {
    }
}
