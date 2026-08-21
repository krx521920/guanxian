package com.guanxian.platform.ecosystem;

import java.util.List;
import java.util.UUID;

record MatchCandidateDraft(
        UUID candidateEnterpriseId,
        String supplierCompany,
        String solution,
        int score,
        List<String> reasons) {
    MatchCandidateDraft {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
