package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.List;
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
        Instant updatedAt) {
    public OfferingView {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        qualifications = qualifications == null ? List.of() : List.copyOf(qualifications);
    }
}
