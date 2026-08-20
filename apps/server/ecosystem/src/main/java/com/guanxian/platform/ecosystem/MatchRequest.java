package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MatchRequest(
        @NotBlank @Size(max = 200) String demandCompany,
        @NotBlank @Size(max = 300) String demandTitle,
        @NotBlank @Size(max = 100) String scene,
        @Size(max = 1000) String requirements,
        @Min(1) @Max(20) Integer limit) {
}
