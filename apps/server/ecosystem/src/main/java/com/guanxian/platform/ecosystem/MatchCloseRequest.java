package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MatchCloseRequest(@NotBlank @Size(max = 1000) String reason) {
}
