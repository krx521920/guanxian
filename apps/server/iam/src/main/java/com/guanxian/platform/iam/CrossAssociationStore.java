package com.guanxian.platform.iam;

import com.guanxian.platform.shared.security.ActorScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CrossAssociationStore {
    List<CrossAssociationDtos.AccessRequestView> accessRequests();

    Optional<CrossAssociationDtos.AccessRequestView> accessRequest(UUID id);

    CrossAssociationDtos.AccessRequestView insertAccessRequest(
            UUID applicantAssociationId, UUID targetAssociationId, String reason, ActorScope actor, Instant now);

    CrossAssociationDtos.AccessRequestView reviewAccessRequest(
            UUID id, String status, String comment, ActorScope actor, Instant now);

    List<CrossAssociationDtos.RelationshipView> relationships();

    Optional<CrossAssociationDtos.RelationshipView> relationship(UUID sourceAssociationId, UUID targetAssociationId);

    CrossAssociationDtos.RelationshipView establishRelationship(
            UUID sourceAssociationId, UUID targetAssociationId, boolean allowMemberData,
            Instant expiresAt, ActorScope actor, Instant now);

    CrossAssociationDtos.RelationshipView updateRelationship(
            UUID sourceAssociationId, UUID targetAssociationId, long expectedVersion,
            String status, Instant expiresAt, Instant suspendedAt, Instant revokedAt,
            String revokeReason, ActorScope actor, Instant now);

    List<CrossAssociationDtos.SharePolicyView> sharePolicies();

    Optional<CrossAssociationDtos.SharePolicyView> sharePolicy(UUID id);

    CrossAssociationDtos.SharePolicyView insertSharePolicy(
            UUID sourceAssociationId, CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor, Instant now);

    CrossAssociationDtos.SharePolicyView updateSharePolicy(
            UUID id, long expectedVersion, CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor, Instant now);

    List<CrossAssociationDtos.ConsentView> consents();

    Optional<CrossAssociationDtos.ConsentView> consent(UUID id);

    CrossAssociationDtos.ConsentView insertConsent(
            UUID enterpriseId, CrossAssociationDtos.ConsentCreate request, ActorScope actor, Instant now);

    CrossAssociationDtos.ConsentView revokeConsent(UUID id, ActorScope actor, Instant now);

    List<CrossAssociationDtos.RecommendationView> recommendations();

    Optional<CrossAssociationDtos.RecommendationView> recommendation(UUID id);

    CrossAssociationDtos.RecommendationView insertRecommendation(
            UUID sourceAssociationId, CrossAssociationDtos.RecommendationCreate request, ActorScope actor, Instant now);

    CrossAssociationDtos.RecommendationView reviewRecommendation(
            UUID id, long expectedVersion, String status, String comment, ActorScope actor, Instant now);

    Optional<UUID> enterpriseAssociation(UUID enterpriseId);

    boolean associationExists(UUID associationId);

    void audit(ActorScope actor, UUID associationId, UUID enterpriseId,
               String action, String resourceType, Object resourceId, Object details);
}
