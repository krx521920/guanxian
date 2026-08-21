package com.guanxian.platform.ecosystem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OutcomeArchiveView(
        UUID id,
        UUID matchId,
        String title,
        String summary,
        BigDecimal contractAmount,
        String resultType,
        String visibility,
        String archivedBySubject,
        Instant archivedAt,
        long version) {
}
