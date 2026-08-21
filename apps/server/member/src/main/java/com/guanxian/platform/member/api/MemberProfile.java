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
        Instant updatedAt) {

    public MemberProfile {
        if (associationId == null) {
            throw new IllegalArgumentException("associationId is required");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        visibility = visibility == null ? "MEMBERS" : visibility;
    }
}
