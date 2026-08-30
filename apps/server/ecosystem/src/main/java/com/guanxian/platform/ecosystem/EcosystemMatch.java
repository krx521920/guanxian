package com.guanxian.platform.ecosystem;

import java.util.List;

public record EcosystemMatch(
        String id,
        String demandCompany,
        String demandTitle,
        String scene,
        String supplierCompany,
        String solution,
        Integer score,
        List<String> reasons,
        String state,
        String updatedAt) {
}
