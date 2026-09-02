package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MatchFeedbackRequest(
        @Min(1) @Max(5) Integer rating,
        @NotBlank @Pattern(regexp = "SUCCESS|NO_DEAL|WITHDRAWN") String outcome,
        @Size(max = 1000) String closeReason,
        @Size(max = 3000) String comment) {
}
