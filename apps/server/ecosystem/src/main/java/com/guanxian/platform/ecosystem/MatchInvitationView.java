package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.UUID;

public record MatchInvitationView(
        UUID id,
        UUID matchId,
        UUID senderEnterpriseId,
        UUID recipientEnterpriseId,
        String invitationType,
        String status,
        String message,
        String responseComment,
        String sentBySubject,
        String respondedBySubject,
        Instant expiresAt,
        Instant respondedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
