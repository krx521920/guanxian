package com.guanxian.platform.ai.impact;

import com.guanxian.platform.policy.PolicyApplicability;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyAnalysisEvidence(String basis, long policyVersion, long enterpriseVersion,
                                     Instant capturedAt, List<Reference> references,
                                     PolicyApplicability.Assessment assessment) {
    public PolicyAnalysisEvidence { references = List.copyOf(references); }
    public record Reference(String kind, UUID id, String title, String sourceUrl, String quote) { }
    public record Metadata(String summary, String sourceUrl, long policyVersion, long enterpriseVersion,
                           String audience, String region, LocalDate effectiveOn, String sourceStatus) {
        public static Metadata empty() { return new Metadata(null,null,0,0,null,null,null,null); }
    }
    public boolean summaryOnly() { return "SUMMARY_REFERENCE".equals(basis); }
}
