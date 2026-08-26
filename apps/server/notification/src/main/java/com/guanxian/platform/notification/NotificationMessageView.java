package com.guanxian.platform.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationMessageView(
        UUID id,
        UUID userId,
        UUID associationId,
        String notificationType,
        String title,
        String body,
        String resourceType,
        UUID resourceId,
        String status,
        Instant readAt,
        Instant createdAt,
        Instant deliveredAt) {
}
