package com.guanxian.platform.ecosystem;

import java.time.Instant;
import java.util.List;
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
        int score,
        List<String> reasons,
        String state,
        String closedReason,
        long version,
        Instant updatedAt) {
    public PersistedMatchView {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
