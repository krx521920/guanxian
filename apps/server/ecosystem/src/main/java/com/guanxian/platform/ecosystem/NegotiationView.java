package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.UUID;

public record NegotiationView(
        UUID id,
        UUID matchId,
        UUID enterpriseId,
        String stage,
        String summary,
        String nextAction,
        Instant nextActionAt,
        String recordedBySubject,
        Instant createdAt,
        long version) {
}
