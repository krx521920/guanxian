package com.guanxian.platform.collaboration;

import java.time.Instant;
import java.util.Map;

public record CollaborationHistoryView(
        long id,
        long version,
        String action,
        String actorSubject,
        Map<String, Object> snapshot,
        Instant occurredAt) {
    public CollaborationHistoryView {
        snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }
}
