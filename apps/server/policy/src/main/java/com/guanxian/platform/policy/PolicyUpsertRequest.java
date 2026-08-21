package com.guanxian.platform.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PolicyUpsertRequest(
        UUID associationId,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 200) String authority,
        @Size(max = 100) String documentNumber,
        @Size(max = 64) String level,
        @Size(max = 100) String category,
        LocalDate publishDate,
        LocalDate effectiveDate,
        @Size(max = 2000) String sourceUrl,
        @Size(max = 10000) String summary,
        List<@Size(max = 100) String> tags,
        String visibility) {
}
