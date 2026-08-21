package com.guanxian.platform.member.internal;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AuditTrail {
    void record(ActorScope actor, String action, String resourceType, String resourceId,
                UUID associationId, UUID enterpriseId, Map<String, Object> details);

    void recordReview(ActorScope actor, UUID associationId, UUID enterpriseId, String previousStatus, String decision, String comment);

    List<AuditRecord> findVisible(ActorScope actor, UUID enterpriseId, int limit);
}
