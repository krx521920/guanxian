package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record OfferingView(
        UUID id,
        UUID enterpriseId,
        String enterpriseName,
        String name,
        String kind,
        String description,
        List<String> scenarios,
        List<String> qualifications,
        String visibility,
        String status,
        long version,
        boolean disabled,
        boolean deleted,
        Instant deletedAt,
        Instant updatedAt,
        Set<String> allowedActions) {
    public OfferingView(
            UUID id, UUID enterpriseId, String enterpriseName, String name, String kind,
            String description, List<String> scenarios, List<String> qualifications,
            String visibility, String status, long version, boolean disabled, Instant updatedAt) {
        this(id, enterpriseId, enterpriseName, name, kind, description, scenarios, qualifications,
                visibility, status, version, disabled, false, null, updatedAt, Set.of());
    }

    public OfferingView(
            UUID id, UUID enterpriseId, String enterpriseName, String name, String kind,
            String description, List<String> scenarios, List<String> qualifications,
            String visibility, String status, long version, boolean disabled,
            boolean deleted, Instant deletedAt, Instant updatedAt) {
        this(id, enterpriseId, enterpriseName, name, kind, description, scenarios, qualifications,
                visibility, status, version, disabled, deleted, deletedAt, updatedAt, Set.of());
    }

    public OfferingView {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        qualifications = qualifications == null ? List.of() : List.copyOf(qualifications);
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
    }
}
