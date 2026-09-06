package com.guanxian.platform.tender;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record TenderUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 200) String purchaser,
        @Size(max = 200) String agency,
        @Size(max = 100) String region,
        @NotBlank @Size(max = 100) String category,
        @Size(max = 10) List<@Size(max = 30) String> keywords,
        @PositiveOrZero Long budget,
        @NotNull LocalDate publishDate,
        LocalDate deadline,
        @Size(max = 200) String source,
        @Size(max = 2000) String sourceUrl,
        @Size(max = 32) String status) {
}
