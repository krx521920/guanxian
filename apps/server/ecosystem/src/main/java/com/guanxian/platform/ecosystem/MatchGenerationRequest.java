package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MatchGenerationRequest(@Min(1) @Max(20) Integer limit) {
}
