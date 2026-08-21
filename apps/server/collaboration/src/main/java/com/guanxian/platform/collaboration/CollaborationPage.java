package com.guanxian.platform.collaboration;

import java.util.List;

public record CollaborationPage<T>(List<T> items, long total, int page, int size) {
    public CollaborationPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
