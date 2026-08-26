package com.guanxian.platform.collaboration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CollaborationActivityRequest(
        @NotBlank @Size(max = 32) String type,
        @NotBlank @Size(max = 3000) String detail) {
}
