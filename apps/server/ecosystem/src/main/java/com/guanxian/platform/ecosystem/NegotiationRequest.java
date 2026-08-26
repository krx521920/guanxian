package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record NegotiationRequest(
        @NotBlank @Size(max = 32) String stage,
        @NotBlank @Size(max = 5000) String summary,
        @Size(max = 1000) String nextAction,
        Instant nextActionAt) {
}
