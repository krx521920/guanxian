package com.guanxian.platform.member.internal;

import java.util.List;

public record AuditPage(
        List<AuditRecord> items,
        long total,
        int page,
        int size,
        long snapshotId) {

    public AuditPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
