package com.guanxian.platform.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class EnterpriseInvitations {
    private EnterpriseInvitations() { }
    record Create(@NotNull UUID enterpriseId, @NotBlank @Size(max = 100) String username) { }
    record Token(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "InvitationToken[redacted]"; }
    }
    record Claim(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token, boolean confirmed) {
        @Override public String toString() { return "InvitationClaim[redacted]"; }
    }
    record Review(@NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
                  @NotBlank @Size(max = 1000) String note) { }
    record Identity(String subject, String username, String displayName) { }
    record View(UUID id, UUID enterpriseId, String enterpriseName, String associationName, String username,
                String status, long version, Instant createdAt, Instant expiresAt, String claimantName,
                String claimantSubject, Instant claimedAt, String reviewNote, UUID accountId) { }
    record Issued(View invitation, String token) {
        @Override public String toString() { return "IssuedInvitation[id=" + invitation.id() + ", token=redacted]"; }
    }
    record Page(List<View> items, long total, int page, int size) { }
}
