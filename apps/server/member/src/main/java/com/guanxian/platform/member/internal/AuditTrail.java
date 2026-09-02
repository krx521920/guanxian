package com.guanxian.platform.member.internal;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AuditTrail {
    int MAX_PAGE = 10_000;

    void record(ActorScope actor, String action, String resourceType, String resourceId,
                UUID associationId, UUID enterpriseId, Map<String, Object> details);

    void recordReview(
            ActorScope actor,
            UUID associationId,
            UUID enterpriseId,
            long newVersion,
            String previousStatus,
            String decision,
            String comment);

    List<AuditRecord> findVisible(ActorScope actor, UUID enterpriseId, int limit);

    default AuditPage pageVisible(
            ActorScope actor, UUID enterpriseId, int page, int size) {
        return pageVisible(actor, enterpriseId, page, size, null);
    }

    default AuditPage pageVisible(
            ActorScope actor, UUID enterpriseId, int page, int size, Long requestedSnapshotId) {
        int safePage = Math.min(MAX_PAGE, Math.max(0, page));
        int safeSize = Math.min(500, Math.max(1, size));
        List<AuditRecord> visible = findVisible(actor, enterpriseId, Integer.MAX_VALUE);
        long snapshotId = requestedSnapshotId == null
                ? visible.stream().mapToLong(AuditRecord::id).max().orElse(0)
                : Math.max(0, requestedSnapshotId);
        visible = visible.stream().filter(record -> record.id() <= snapshotId).toList();
        long offset = (long) safePage * safeSize;
        if (offset >= visible.size()) {
            return new AuditPage(List.of(), visible.size(), safePage, safeSize, snapshotId);
        }
        int from = Math.toIntExact(offset);
        return new AuditPage(
                visible.subList(from, Math.min(visible.size(), from + safeSize)),
                visible.size(), safePage, safeSize, snapshotId);
    }
}
