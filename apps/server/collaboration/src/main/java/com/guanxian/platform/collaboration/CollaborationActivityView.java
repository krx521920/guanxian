package com.guanxian.platform.collaboration;

import java.time.Instant;

public record CollaborationActivityView(
        long id,
        String type,
        String detail,
        String actorSubject,
        Instant occurredAt) {
}
