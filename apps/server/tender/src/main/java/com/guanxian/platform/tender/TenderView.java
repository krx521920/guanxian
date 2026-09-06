package com.guanxian.platform.tender;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TenderView(
        String id,
        UUID associationId,
        String title,
        String purchaser,
        String agency,
        String region,
        String category,
        List<String> keywords,
        Long budget,
        LocalDate publishDate,
        LocalDate deadline,
        String source,
        String sourceUrl,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
