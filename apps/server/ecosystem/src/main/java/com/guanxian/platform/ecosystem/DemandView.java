package com.guanxian.platform.ecosystem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
        boolean deleted,
        Instant deletedAt,
        Instant updatedAt,
        Set<String> allowedActions) {
    public DemandView(
            UUID id, UUID enterpriseId, String enterpriseName, String title, String description,
            List<String> scenarios, List<String> requiredCapabilities, String visibility,
            BigDecimal budgetMin, BigDecimal budgetMax, Instant responseDeadline, String status,
            String closeReason, long version, boolean disabled, Instant updatedAt) {
        this(id, enterpriseId, enterpriseName, title, description, scenarios, requiredCapabilities,
                visibility, budgetMin, budgetMax, responseDeadline, status, closeReason, version,
                disabled, false, null, updatedAt, Set.of());
    }

    public DemandView(
            UUID id, UUID enterpriseId, String enterpriseName, String title, String description,
            List<String> scenarios, List<String> requiredCapabilities, String visibility,
            BigDecimal budgetMin, BigDecimal budgetMax, Instant responseDeadline, String status,
            String closeReason, long version, boolean disabled, boolean deleted,
            Instant deletedAt, Instant updatedAt) {
        this(id, enterpriseId, enterpriseName, title, description, scenarios, requiredCapabilities,
                visibility, budgetMin, budgetMax, responseDeadline, status, closeReason, version,
                disabled, deleted, deletedAt, updatedAt, Set.of());
    }

    public DemandView {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
    }
}
