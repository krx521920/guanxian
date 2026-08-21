package com.guanxian.platform.member.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

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
                regexp = "(?i)^\\s*(PRIVATE|ASSOCIATION|PARTNERS|MEMBERS|PUBLIC)?\\s*$",
                message = "must be PRIVATE, ASSOCIATION, PARTNERS, MEMBERS or PUBLIC")
        String visibility,
        @Size(max = 30)
        @Pattern(
                regexp = "(?i)^\\s*(ACTIVE|PENDING_REVIEW|INCOMPLETE|DISABLED)?\\s*$",
                message = "must be ACTIVE, PENDING_REVIEW, INCOMPLETE or DISABLED")
        String status,
        UUID associationId) {

    public MemberUpsertRequest(
            String name,
            String unifiedSocialCreditCode,
            String category,
            String address,
            String contactName,
            String contactPhone,
            String introduction,
            List<String> capabilities,
            List<String> products,
            List<String> cooperationNeeds,
            String status) {
        this(name, unifiedSocialCreditCode, category, address, contactName, contactPhone, introduction,
                capabilities, products, cooperationNeeds, null, status, null);
    }
}
