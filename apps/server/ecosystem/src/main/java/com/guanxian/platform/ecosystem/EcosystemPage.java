package com.guanxian.platform.ecosystem;

import java.util.List;

public record EcosystemPage<T>(List<T> items, long total, int page, int size) {
    public EcosystemPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
