package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CloseDemandRequest(@NotBlank @Size(max = 1000) String reason) {
}
