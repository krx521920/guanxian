package com.guanxian.platform.ecosystem;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record MatchInvitationRequest(
        @NotNull UUID recipientEnterpriseId,
        @NotNull @Pattern(regexp = "ENTERPRISE|ASSOCIATION_RECOMMENDATION") String invitationType,
        @Size(max = 2000) String message,
        Instant expiresAt) {
}
