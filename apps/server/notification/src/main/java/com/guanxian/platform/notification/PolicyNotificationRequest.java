package com.guanxian.platform.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PolicyNotificationRequest(
        UUID associationId,
        @NotNull UUID policyId,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 5000) String body,
        @NotBlank @Size(max = 120) String idempotencyKey) {
}
