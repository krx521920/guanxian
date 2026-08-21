package com.guanxian.platform.policy;

import jakarta.validation.constraints.Size;

public record PolicyReviewRequest(boolean approved, @Size(max = 2000) String comment) {
}
