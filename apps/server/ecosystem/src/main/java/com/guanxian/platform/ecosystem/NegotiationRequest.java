package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record NegotiationRequest(
        @NotBlank @Pattern(regexp = "INITIAL_CONTACT|TECHNICAL_EXCHANGE|COMMERCIAL_NEGOTIATION|CONTRACTING|CONTRACT_SIGNED|TERMINATED") String stage,
        @NotBlank @Size(max = 5000) String summary,
        @Size(max = 1000) String nextAction,
        Instant nextActionAt) {
}
