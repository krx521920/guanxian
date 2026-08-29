package com.guanxian.platform.member.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberProfile(
        UUID id,
        UUID associationId,
        String name,
        String unifiedSocialCreditCode,
        String category,
        String address,
        String contactName,
        String contactPhone,
        String introduction,
        List<String> capabilities,
        List<String> products,
        List<String> cooperationNeeds,
        String visibility,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String deletedBySubject,
        String statusBeforeDelete) {

    public MemberProfile {
        if (associationId == null) {
            throw new IllegalArgumentException("associationId is required");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        visibility = visibility == null ? "MEMBERS" : visibility;
        if ((deletedAt == null) != (deletedBySubject == null)
                || (deletedAt == null) != (statusBeforeDelete == null)) {
            throw new IllegalArgumentException("member deletion metadata must be complete");
        }
        if (deletedAt != null && !"DELETED".equals(status)) {
            throw new IllegalArgumentException("deleted member must have DELETED status");
        }
    }

    public boolean deleted() {
        return deletedAt != null;
    }
}
