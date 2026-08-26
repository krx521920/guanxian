package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.UUID;

public record MatchFeedbackView(
        UUID id,
        UUID matchId,
        UUID enterpriseId,
        Integer rating,
        String outcome,
        String closeReason,
        String comment,
        String submittedBySubject,
        Instant submittedAt) {
}
