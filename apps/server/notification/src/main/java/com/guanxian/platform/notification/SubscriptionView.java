package com.guanxian.platform.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SubscriptionView(
        UUID id,
        UUID userId,
        UUID associationId,
        String subscriptionType,
        Map<String, Object> filters,
        List<String> channels,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
