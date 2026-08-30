package com.guanxian.platform.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CrossAssociationDtos {
    private CrossAssociationDtos() {
    }

    record AccessRequestCreate(
            UUID applicantAssociationId,
            @NotNull UUID targetAssociationId,
            @Size(max = 2000) String reason) {
    }

    record AccessRequestReview(
            @NotNull AccessDecision decision,
            @Size(max = 2000) String comment,
            Instant relationshipExpiresAt,
            Boolean allowMemberData) {
    }

    enum AccessDecision { APPROVE, REJECT }

    record AccessRequestCancel(
            @Size(max = 2000) String reason) {
    }

    record AccessRequestView(
            UUID id,
            UUID applicantAssociationId,
            UUID targetAssociationId,
            String reason,
            String status,
            String requestedBySubject,
            String reviewedBySubject,
            String reviewComment,
            Instant requestedAt,
            Instant reviewedAt) {
    }

    record RelationshipChange(
            @NotNull RelationshipAction action,
            Instant expiresAt,
            @Size(max = 1000) String reason) {
    }

    enum RelationshipAction { ACTIVATE, SUSPEND, REVOKE, EXPIRE }

    record RelationshipView(
            UUID sourceAssociationId,
            UUID targetAssociationId,
            String status,
            boolean allowMemberData,
            Instant expiresAt,
            Instant suspendedAt,
            UUID suspendedByAssociationId,
            String suspendedBySubject,
            Instant revokedAt,
            String revokedBySubject,
            String revokeReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    record SharePolicyUpsert(
            UUID sourceAssociationId,
            @NotNull UUID targetAssociationId,
            @NotBlank @Size(max = 64) String resourceType,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> visibleFields,
            Instant validFrom,
            Instant expiresAt,
            String status) {
    }

    record SharePolicyView(
            UUID id,
            UUID sourceAssociationId,
            UUID targetAssociationId,
            String resourceType,
            List<String> visibleFields,
            String status,
            Instant validFrom,
            Instant expiresAt,
            String createdBySubject,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    record SharePolicyStatusChange(
            @NotNull SharePolicyStatus status) {
    }

    enum SharePolicyStatus { ACTIVE, SUSPENDED }

    record ConsentCreate(
            UUID enterpriseId,
            @NotNull UUID targetAssociationId,
            @NotBlank @Size(max = 64) String resourceType,
            @NotNull UUID resourceId,
            Instant expiresAt) {
    }

    record ConsentView(
            UUID id,
            UUID enterpriseId,
            UUID targetAssociationId,
            String resourceType,
            UUID resourceId,
            String status,
            String grantedBySubject,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt) {
    }

    record ConsentTargetView(
            UUID targetAssociationId,
            String resourceType,
            Instant policyExpiresAt) {
    }

    record RecommendationCreate(
            UUID sourceAssociationId,
            @NotNull UUID targetAssociationId,
            UUID demandId,
            UUID matchId,
            @NotBlank @Size(max = 2000) String summary) {
    }

    record RecommendationReview(
            @NotNull RecommendationDecision decision,
            @Size(max = 2000) String comment) {
    }

    enum RecommendationDecision { APPROVE, REJECT }

    record RecommendationView(
            UUID id,
            UUID sourceAssociationId,
            UUID targetAssociationId,
            UUID demandId,
            UUID matchId,
            String status,
            String summary,
            String createdBySubject,
            String reviewedBySubject,
            String reviewComment,
            Instant createdAt,
            Instant reviewedAt,
            long version) {
    }
}
