package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EcosystemCatalogStore {
    default List<OfferingView> listOfferings(ActorScope actor, String query, boolean includeDeleted, long offset, int limit) {
        return listOfferings(actor, query, includeDeleted, offset, limit, false);
    }
    List<OfferingView> listOfferings(ActorScope actor, String query, boolean includeDeleted, long offset, int limit, boolean ownOnly);

    default long countOfferings(ActorScope actor, String query, boolean includeDeleted) {
        return countOfferings(actor, query, includeDeleted, false);
    }
    long countOfferings(ActorScope actor, String query, boolean includeDeleted, boolean ownOnly);

    Optional<OfferingView> findOffering(UUID id, ActorScope actor, boolean includeDeleted);

    OfferingView createOffering(UUID enterpriseId, OfferingUpsertRequest request, ActorScope actor);

    Optional<OfferingView> updateOffering(
            UUID id, long expectedVersion, OfferingUpsertRequest request, ActorScope actor);

    Optional<OfferingView> transitionOffering(
            UUID id, long expectedVersion, String targetStatus, ActorScope actor);

    Optional<OfferingView> softDeleteOffering(UUID id, long expectedVersion, ActorScope actor);

    Optional<OfferingView> restoreOffering(UUID id, long expectedVersion, ActorScope actor);

    default List<DemandView> listDemands(ActorScope actor, String query, boolean includeDeleted, long offset, int limit) {
        return listDemands(actor, query, includeDeleted, offset, limit, false);
    }
    List<DemandView> listDemands(ActorScope actor, String query, boolean includeDeleted, long offset, int limit, boolean ownOnly);

    default long countDemands(ActorScope actor, String query, boolean includeDeleted) {
        return countDemands(actor, query, includeDeleted, false);
    }
    long countDemands(ActorScope actor, String query, boolean includeDeleted, boolean ownOnly);

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

    default boolean enterpriseHistoricallyBelongsToAssociation(UUID enterpriseId, UUID associationId) {
        return enterpriseBelongsToAssociation(enterpriseId, associationId);
    }

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
