package com.guanxian.platform.notification;

import java.util.UUID;

public record PolicyNotificationResult(
        UUID policyId,
        UUID associationId,
        int recipientCount,
        boolean duplicate) {
}
