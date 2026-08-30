package com.guanxian.platform.iam;

import java.time.Instant;
import java.util.UUID;

record AccessBindingView(
        UUID id,
        String externalSubject,
        String username,
        String displayName,
        UUID associationId,
        String associationName,
        UUID enterpriseId,
        String enterpriseName,
        String status,
        long version,
        boolean bound,
        Instant updatedAt) {
}
