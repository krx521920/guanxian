package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @NotNull Boolean approved,
        @Size(max = 1000) String comment) {
}
