package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PersistedMatchView(
        UUID id,
        UUID demandId,
        UUID demandEnterpriseId,
        UUID candidateEnterpriseId,
        String demandCompany,
        String demandTitle,
        String scene,
        String supplierCompany,
        String solution,
        Integer score,
        List<String> reasons,
        String state,
        Instant recommendedAt,
        Instant demandConfirmedAt,
        Instant candidateConfirmedAt,
        String closedReason,
        long version,
        Instant updatedAt,
        Set<String> allowedActions) {
    public PersistedMatchView {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
    }
}
