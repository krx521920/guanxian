package com.guanxian.platform.member.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberProfile(
        UUID id,
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
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public MemberProfile {
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
    }
}
