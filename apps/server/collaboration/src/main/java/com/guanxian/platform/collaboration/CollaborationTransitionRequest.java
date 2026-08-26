package com.guanxian.platform.collaboration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CollaborationTransitionRequest(
        @NotBlank String targetStage,
        @Size(max = 1000) String detail) {
}
