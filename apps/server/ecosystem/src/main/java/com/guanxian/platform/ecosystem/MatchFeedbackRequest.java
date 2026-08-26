package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MatchFeedbackRequest(
        @Min(1) @Max(5) Integer rating,
        @NotBlank @Size(max = 32) String outcome,
        @Size(max = 1000) String closeReason,
        @Size(max = 3000) String comment) {
}
