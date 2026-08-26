package com.guanxian.platform.ai.impact;

import java.util.List;

public record PolicyImpactPage(
        List<PolicyImpactAnalysisView> items,
        long total,
        int page,
        int size) {
    public PolicyImpactPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
