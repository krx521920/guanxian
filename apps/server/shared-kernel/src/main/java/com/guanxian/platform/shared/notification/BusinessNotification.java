package com.guanxian.platform.shared.notification;

import java.util.List;
import java.util.UUID;

public record BusinessNotification(
        UUID associationId,
        List<UUID> enterpriseIds,
        boolean includeAssociationStaff,
        String notificationType,
        String title,
        String body,
        String resourceType,
        UUID resourceId,
        long resourceVersion,
        String idempotencyKey) {
    public BusinessNotification {
        enterpriseIds = enterpriseIds == null ? List.of() : enterpriseIds.stream().distinct().toList();
    }
}
