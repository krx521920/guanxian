package com.guanxian.platform.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record AccessBindingRequest(
        @NotBlank @Size(max = 200) String externalSubject,
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 100) String displayName,
        UUID associationId,
        UUID enterpriseId,
        @Size(max = 254) String email) {
}
