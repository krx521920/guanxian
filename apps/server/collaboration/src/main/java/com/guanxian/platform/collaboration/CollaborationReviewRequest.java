package com.guanxian.platform.collaboration;

import jakarta.validation.constraints.Size;

public record CollaborationReviewRequest(
        boolean approved,
        @Size(max = 1000) String comment) {
}
