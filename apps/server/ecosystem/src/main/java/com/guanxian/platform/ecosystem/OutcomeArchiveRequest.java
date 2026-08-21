package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OutcomeArchiveRequest(
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 5000) String summary,
        @DecimalMin("0.00") BigDecimal contractAmount,
        @NotBlank @Size(max = 32) String resultType,
        @Pattern(regexp = "PRIVATE|ENTERPRISES|ASSOCIATION|PARTNERS|PUBLIC") String visibility) {
}
