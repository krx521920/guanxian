package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.web.MemberUpsertRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProfileWorkflow {
    private ProfileWorkflow() { }
    public record Draft(UUID id, long baseVersion, MemberUpsertRequest content, String status,
                        Set<String> editors, String submittedBy, String reviewNote,
                        String reviewedBy, Instant submittedAt, Instant reviewedAt) { }
    public record Approved(UUID id, MemberProfile profile, Set<String> editors, String submittedBy,
                           String reviewedBy, Instant approvedAt, String consentedBy,
                           Instant consentedAt, long consentEpoch) { }
    /** Explicit anonymous field allowlist. Do not replace with MemberProfile. */
    public record PublicProfile(UUID id, String name, String category, String introduction,
                                List<String> capabilities, List<String> products, List<String> services,
                                List<String> applicationScenarios, UUID publicationId, Instant publishedAt) {
        public static PublicProfile from(MemberProfile p, UUID publicationId, Instant time) {
            return new PublicProfile(p.id(), p.name(), p.category(), p.introduction(), p.capabilities(),
                    p.products(), p.services(), p.applicationScenarios(), publicationId, time);
        }
    }
    public record State(Draft draft, Approved approved, PublicProfile publication) {
        public static State empty() { return new State(null, null, null); }
    }
    public record Row(long version, long epoch, boolean published, State state) { }
    public record View(MemberProfile official, Draft draft, Approved approved, PublicProfile publication,
                       long version, boolean published, boolean canEdit, boolean canReview,
                       boolean canConsent, boolean canPublish, boolean canWithdraw) { }
}
