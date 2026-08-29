package com.guanxian.platform.member.internal;

import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "guanxian.member.repository", havingValue = "memory")
class InMemoryAuditTrail implements AuditTrail {
    private final AtomicLong sequence = new AtomicLong();
    private final List<AuditRecord> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(ActorScope actor, String action, String resourceType, String resourceId,
                       UUID associationId, UUID enterpriseId, Map<String, Object> details) {
        entries.add(new AuditRecord(
                sequence.incrementAndGet(), actor.subject(), actor.username(), associationId, enterpriseId,
                action, resourceType, resourceId,
                details.get("newVersion") instanceof Number number && number.longValue() >= 0
                        ? number.longValue() : null,
                "SUCCESS", Map.copyOf(details),
                MDC.get("requestId") == null ? "internal" : MDC.get("requestId"), Instant.now()));
    }

    @Override
    public void recordReview(
            ActorScope actor, UUID associationId, UUID enterpriseId, String previousStatus, String decision, String comment) {
        record(actor, "MEMBER_REVIEW", "ENTERPRISE", enterpriseId.toString(), associationId, enterpriseId,
                Map.of("previousStatus", previousStatus, "decision", decision,
                        "comment", comment == null ? "" : comment));
    }

    @Override
    public List<AuditRecord> findVisible(ActorScope actor, UUID enterpriseId, int limit) {
        return entries.reversed().stream()
                .filter(entry -> actor.isSystemAdmin()
                        || entry.associationId() != null && entry.associationId().equals(actor.associationId()))
                .filter(entry -> enterpriseId == null || enterpriseId.equals(entry.enterpriseId()))
                .limit(limit)
                .toList();
    }
}
