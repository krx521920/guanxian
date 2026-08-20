package com.guanxian.platform.member.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MemberUpsertRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 32) String unifiedSocialCreditCode,
        @NotBlank @Size(max = 100) String category,
        @Size(max = 300) String address,
        @Size(max = 50) String contactName,
        @Size(max = 50) String contactPhone,
        @Size(max = 2000) String introduction,
        @Size(max = 50) List<@Size(max = 100) String> capabilities,
        @Size(max = 50) List<@Size(max = 100) String> products,
        @Size(max = 50) List<@Size(max = 200) String> cooperationNeeds,
        @Size(max = 30)
        @Pattern(
                regexp = "(?i)^\\s*(ACTIVE|PENDING_REVIEW|INCOMPLETE|DISABLED)?\\s*$",
                message = "must be ACTIVE, PENDING_REVIEW, INCOMPLETE or DISABLED")
        String status) {
}
