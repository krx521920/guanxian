package com.guanxian.platform.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyView(
        String id,
        String title,
        String authority,
        String documentNumber,
        String level,
        String category,
        LocalDate publishDate,
        LocalDate effectiveDate,
        String sourceUrl,
        String status,
        String summary,
        List<String> tags,
        UUID associationId,
        String visibility,
        long version,
        boolean disabled,
        boolean deleted,
        Instant updatedAt) {
}
