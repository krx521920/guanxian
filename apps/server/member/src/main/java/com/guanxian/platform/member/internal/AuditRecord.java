package com.guanxian.platform.member.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecord(
        long id,
        String actorSubject,
        String actorUsername,
        UUID associationId,
        UUID enterpriseId,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> details,
        String requestId,
        Instant occurredAt) {
}
