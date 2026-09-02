package com.guanxian.platform.iam;

import com.guanxian.platform.shared.security.ActorScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface CrossAssociationStore {
    List<CrossAssociationDtos.AccessRequestView> accessRequests();

    Optional<CrossAssociationDtos.AccessRequestView> accessRequest(UUID id);

    CrossAssociationDtos.AccessRequestView insertAccessRequest(
            UUID applicantAssociationId, UUID targetAssociationId, String reason, ActorScope actor, Instant now);

    CrossAssociationDtos.AccessRequestView reviewAccessRequest(
            UUID id, long expectedVersion, String status, String comment, ActorScope actor, Instant now);

    CrossAssociationDtos.AccessRequestView cancelAccessRequest(
            UUID id, long expectedVersion, String reason, ActorScope actor, Instant now);

    List<CrossAssociationDtos.RelationshipView> relationships();

    Optional<CrossAssociationDtos.RelationshipView> relationship(UUID sourceAssociationId, UUID targetAssociationId);

    CrossAssociationDtos.RelationshipView establishRelationship(
            UUID sourceAssociationId, UUID targetAssociationId, boolean allowMemberData,
            Instant expiresAt, ActorScope actor, Instant now);

    CrossAssociationDtos.RelationshipView updateRelationship(
            UUID sourceAssociationId, UUID targetAssociationId, long expectedVersion,
            String status, Instant expiresAt, Instant suspendedAt, UUID suspendedByAssociationId,
            String suspendedBySubject, Instant revokedAt, String revokeReason, ActorScope actor, Instant now);

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

    CrossAssociationDtos.ConsentView revokeConsent(UUID id, long expectedVersion, ActorScope actor, Instant now);

    List<CrossAssociationDtos.ConsentView> materializeExpiredConsents(
            UUID enterpriseId, UUID targetAssociationId, String resourceType, UUID resourceId, Instant now);

    List<CrossAssociationDtos.ConsentView> revokeActiveConsentsBetweenAssociations(
            UUID sourceAssociationId, UUID targetAssociationId, Instant now);

    Optional<Set<String>> authorizedFields(
            UUID targetAssociationId, UUID enterpriseId, String resourceType, UUID resourceId, Instant now);

    List<CrossAssociationDtos.RecommendationView> recommendations();

    Optional<CrossAssociationDtos.RecommendationView> recommendation(UUID id);

    CrossAssociationDtos.RecommendationView insertRecommendation(
            UUID sourceAssociationId, CrossAssociationDtos.RecommendationCreate request, ActorScope actor, Instant now);

    CrossAssociationDtos.RecommendationView reviewRecommendation(
            UUID id, long expectedVersion, String status, String comment, ActorScope actor, Instant now);

    Optional<UUID> enterpriseAssociation(UUID enterpriseId);

    boolean associationExists(UUID associationId);

    boolean resourceOwnedByEnterprise(String resourceType, UUID resourceId, UUID enterpriseId);

    Optional<DemandOwnership> demandOwnership(UUID demandId);

    Optional<MatchOwnership> matchOwnership(UUID matchId);

    void audit(ActorScope actor, UUID associationId, UUID enterpriseId,
               String action, String resourceType, Object resourceId, Long resourceVersion, Object details);

    default void audit(ActorScope actor, UUID associationId, UUID enterpriseId,
                       String action, String resourceType, Object resourceId, Object details) {
        audit(actor, associationId, enterpriseId, action, resourceType, resourceId, null, details);
    }

    record DemandOwnership(UUID demandId, UUID enterpriseId, UUID associationId) {
    }

    record MatchOwnership(
            UUID matchId,
            UUID demandId,
            UUID demandEnterpriseId,
            UUID demandAssociationId,
            UUID candidateEnterpriseId,
            UUID candidateAssociationId) {
        boolean belongsToEnterprise(UUID enterpriseId) {
            return enterpriseId != null
                    && (enterpriseId.equals(demandEnterpriseId) || enterpriseId.equals(candidateEnterpriseId));
        }
    }
}
