package com.guanxian.platform.ecosystem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DemandView(
        UUID id,
        UUID enterpriseId,
        String enterpriseName,
        String title,
        String description,
        List<String> scenarios,
        List<String> requiredCapabilities,
        String visibility,
        BigDecimal budgetMin,
        BigDecimal budgetMax,
        Instant responseDeadline,
        String status,
        String closeReason,
        long version,
        boolean disabled,
        Instant updatedAt) {
    public DemandView {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
    }
}
