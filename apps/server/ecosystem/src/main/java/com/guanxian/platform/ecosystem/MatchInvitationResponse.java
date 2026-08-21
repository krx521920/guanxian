package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MatchInvitationResponse(
        @NotNull Boolean accepted,
        @Size(max = 2000) String comment) {
}
