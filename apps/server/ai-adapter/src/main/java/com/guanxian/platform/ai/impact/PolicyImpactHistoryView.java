package com.guanxian.platform.ai.impact;

import java.time.Instant;
import java.util.Map;

public record PolicyImpactHistoryView(
        long version,
        String action,
        String actorSubject,
        Map<String, Object> snapshot,
        Instant occurredAt) {
}
