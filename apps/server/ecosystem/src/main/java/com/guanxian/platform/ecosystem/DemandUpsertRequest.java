package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DemandUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @Size(max = 30) List<@NotBlank @Size(max = 100) String> scenarios,
        @Size(max = 30) List<@NotBlank @Size(max = 200) String> requiredCapabilities,
        @Pattern(regexp = "PRIVATE|MEMBERS|PARTNERS|PUBLIC|DIRECTED") String visibility,
        @DecimalMin("0.00") BigDecimal budgetMin,
        @DecimalMin("0.00") BigDecimal budgetMax,
        Instant responseDeadline) {
}
