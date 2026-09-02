package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EcosystemCatalogStore {
    List<OfferingView> listOfferings(ActorScope actor, String query, boolean includeDeleted, long offset, int limit);

    long countOfferings(ActorScope actor, String query, boolean includeDeleted);

    Optional<OfferingView> findOffering(UUID id, ActorScope actor, boolean includeDeleted);

    OfferingView createOffering(UUID enterpriseId, OfferingUpsertRequest request, ActorScope actor);

    Optional<OfferingView> updateOffering(
            UUID id, long expectedVersion, OfferingUpsertRequest request, ActorScope actor);

    Optional<OfferingView> transitionOffering(
            UUID id, long expectedVersion, String targetStatus, ActorScope actor);

    Optional<OfferingView> softDeleteOffering(UUID id, long expectedVersion, ActorScope actor);

    Optional<OfferingView> restoreOffering(UUID id, long expectedVersion, ActorScope actor);

    List<DemandView> listDemands(ActorScope actor, String query, boolean includeDeleted, long offset, int limit);

    long countDemands(ActorScope actor, String query, boolean includeDeleted);

    Optional<DemandView> findDemand(UUID id, ActorScope actor, boolean includeDeleted);

    DemandView createDemand(UUID enterpriseId, DemandUpsertRequest request, ActorScope actor);

    Optional<DemandView> updateDemand(
            UUID id, long expectedVersion, DemandUpsertRequest request, ActorScope actor);

    Optional<DemandView> transitionDemand(
            UUID id, long expectedVersion, String targetStatus, String reason, ActorScope actor);

    Optional<DemandView> softDeleteDemand(UUID id, long expectedVersion, ActorScope actor);

    Optional<DemandView> restoreDemand(UUID id, long expectedVersion, ActorScope actor);

    boolean isDemandDeleted(UUID demandId);

    default boolean isDemandOpenForResponse(UUID demandId) {
        return !isDemandDeleted(demandId);
    }

    boolean enterpriseBelongsToAssociation(UUID enterpriseId, UUID associationId);

    void recordChange(
            ActorScope actor,
            String action,
            String resourceType,
            UUID resourceId,
            UUID associationId,
            UUID enterpriseId,
            long version,
            Object snapshot);
}
