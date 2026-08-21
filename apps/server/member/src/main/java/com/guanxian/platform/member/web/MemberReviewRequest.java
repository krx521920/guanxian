package com.guanxian.platform.member.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MemberReviewRequest(
        @NotBlank
        @Pattern(regexp = "ACTIVE|INCOMPLETE|DISABLED", message = "must be ACTIVE, INCOMPLETE or DISABLED")
        String decision,
        @Size(max = 1000) String comment) {
}
