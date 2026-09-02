package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryEcosystemCatalogStore implements EcosystemCatalogStore {
    private final ConcurrentMap<UUID, StoredOffering> offerings = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, StoredDemand> demands = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> enterpriseAssociations = new ConcurrentHashMap<>();
    private final EnterpriseLifecycle enterpriseLifecycle;

    @Autowired
    InMemoryEcosystemCatalogStore(EnterpriseLifecycle enterpriseLifecycle) {
        this.enterpriseLifecycle = enterpriseLifecycle;
    }

    InMemoryEcosystemCatalogStore() {
        this(enterpriseId -> true);
    }

    @Override
    public List<OfferingView> listOfferings(
            ActorScope actor, String query, boolean includeDeleted, long offset, int limit) {
        return offerings.values().stream()
                .filter(item -> canReadEnterpriseHistory(actor, item.value().enterpriseId()))
                .filter(item -> canReadDeletion(actor, item.value().enterpriseId(), item.deleted(), includeDeleted))
                .map(StoredOffering::value)
                .filter(item -> canRead(actor, item.enterpriseId(), item.visibility(), item.status()))
                .filter(item -> matches(query, item.name(), item.description(), item.enterpriseName()))
                .sorted(Comparator.comparing(OfferingView::updatedAt).reversed()
                        .thenComparing(OfferingView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countOfferings(ActorScope actor, String query, boolean includeDeleted) {
        return offerings.values().stream()
                .filter(item -> canReadEnterpriseHistory(actor, item.value().enterpriseId()))
                .filter(item -> canReadDeletion(actor, item.value().enterpriseId(), item.deleted(), includeDeleted))
                .map(StoredOffering::value)
                .filter(item -> canRead(actor, item.enterpriseId(), item.visibility(), item.status()))
                .filter(item -> matches(query, item.name(), item.description(), item.enterpriseName()))
                .count();
    }

    @Override
    public Optional<OfferingView> findOffering(UUID id, ActorScope actor, boolean includeDeleted) {
        StoredOffering item = offerings.get(id);
        if (item == null || !canReadEnterpriseHistory(actor, item.value().enterpriseId())
                || !canReadDeletion(actor, item.value().enterpriseId(), item.deleted(), includeDeleted)
                || !canRead(actor, item.value().enterpriseId(), item.value().visibility(), item.value().status())) {
            return Optional.empty();
        }
        return Optional.of(item.value());
    }

    @Override
    public synchronized OfferingView createOffering(
            UUID enterpriseId, OfferingUpsertRequest request, ActorScope actor) {
        requireCreateScope(enterpriseId, actor);
        UUID id = UUID.randomUUID();
        OfferingView value = new OfferingView(
                id, enterpriseId, null, request.name().trim(), request.kind(),
                clean(request.description()), list(request.scenarios()), list(request.qualifications()),
                visibility(request.visibility(), "MEMBERS"), "DRAFT", 0, false, Instant.now());
        offerings.put(id, new StoredOffering(value, false));
        bindEnterpriseAssociation(enterpriseId, actor);
        return value;
    }

    @Override
    public synchronized Optional<OfferingView> updateOffering(
            UUID id, long expectedVersion, OfferingUpsertRequest request, ActorScope actor) {
        StoredOffering stored = offerings.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        OfferingView old = stored.value();
        OfferingView updated = new OfferingView(
                old.id(), old.enterpriseId(), old.enterpriseName(), request.name().trim(), request.kind(),
                clean(request.description()), list(request.scenarios()), list(request.qualifications()),
                visibility(request.visibility(), old.visibility()), "DRAFT", old.version() + 1,
                false, Instant.now());
        offerings.put(id, new StoredOffering(updated, false));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<OfferingView> transitionOffering(
            UUID id, long expectedVersion, String targetStatus, ActorScope actor) {
        StoredOffering stored = offerings.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        OfferingView old = stored.value();
        OfferingView updated = new OfferingView(
                old.id(), old.enterpriseId(), old.enterpriseName(), old.name(), old.kind(), old.description(),
                old.scenarios(), old.qualifications(), old.visibility(), targetStatus, old.version() + 1,
                "DISABLED".equals(targetStatus), Instant.now());
        offerings.put(id, new StoredOffering(updated, false));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<OfferingView> softDeleteOffering(
            UUID id, long expectedVersion, ActorScope actor) {
        StoredOffering stored = offerings.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        OfferingView old = stored.value();
        Instant deletedAt = Instant.now();
        OfferingView updated = new OfferingView(
                old.id(), old.enterpriseId(), old.enterpriseName(), old.name(), old.kind(), old.description(),
                old.scenarios(), old.qualifications(), old.visibility(), old.status(), old.version() + 1,
                old.disabled(), true, deletedAt, deletedAt);
        offerings.put(id, new StoredOffering(updated, true));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<OfferingView> restoreOffering(
            UUID id, long expectedVersion, ActorScope actor) {
        StoredOffering stored = offerings.get(id);
        if (stored == null || !stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        OfferingView old = stored.value();
        OfferingView updated = copyOffering(old, "DRAFT", old.version() + 1, false);
        offerings.put(id, new StoredOffering(updated, false));
        return Optional.of(updated);
    }

    @Override
    public List<DemandView> listDemands(
            ActorScope actor, String query, boolean includeDeleted, long offset, int limit) {
        return demands.values().stream()
                .filter(item -> canReadEnterpriseHistory(actor, item.value().enterpriseId()))
                .filter(item -> canReadDeletion(actor, item.value().enterpriseId(), item.deleted(), includeDeleted))
                .map(StoredDemand::value)
                .filter(item -> canRead(actor, item.enterpriseId(), item.visibility(), item.status()))
                .filter(item -> matches(query, item.title(), item.description(), item.enterpriseName()))
                .sorted(Comparator.comparing(DemandView::updatedAt).reversed()
                        .thenComparing(DemandView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countDemands(ActorScope actor, String query, boolean includeDeleted) {
        return demands.values().stream()
                .filter(item -> canReadEnterpriseHistory(actor, item.value().enterpriseId()))
                .filter(item -> canReadDeletion(actor, item.value().enterpriseId(), item.deleted(), includeDeleted))
                .map(StoredDemand::value)
                .filter(item -> canRead(actor, item.enterpriseId(), item.visibility(), item.status()))
                .filter(item -> matches(query, item.title(), item.description(), item.enterpriseName()))
                .count();
    }

    @Override
    public Optional<DemandView> findDemand(UUID id, ActorScope actor, boolean includeDeleted) {
        StoredDemand item = demands.get(id);
        if (item == null || !canReadEnterpriseHistory(actor, item.value().enterpriseId())
                || !canReadDeletion(actor, item.value().enterpriseId(), item.deleted(), includeDeleted)
                || !canRead(actor, item.value().enterpriseId(), item.value().visibility(), item.value().status())) {
            return Optional.empty();
        }
        return Optional.of(item.value());
    }

    @Override
    public synchronized DemandView createDemand(
            UUID enterpriseId, DemandUpsertRequest request, ActorScope actor) {
        requireCreateScope(enterpriseId, actor);
        UUID id = UUID.randomUUID();
        DemandView value = new DemandView(
                id, enterpriseId, null, request.title().trim(), request.description().trim(),
                list(request.scenarios()), list(request.requiredCapabilities()),
                visibility(request.visibility(), "MEMBERS"), request.budgetMin(), request.budgetMax(),
                request.responseDeadline(), "DRAFT", null, 0, false, Instant.now());
        demands.put(id, new StoredDemand(value, false));
        bindEnterpriseAssociation(enterpriseId, actor);
        return value;
    }

    @Override
    public synchronized Optional<DemandView> updateDemand(
            UUID id, long expectedVersion, DemandUpsertRequest request, ActorScope actor) {
        StoredDemand stored = demands.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        DemandView old = stored.value();
        DemandView updated = new DemandView(
                old.id(), old.enterpriseId(), old.enterpriseName(), request.title().trim(),
                request.description().trim(), list(request.scenarios()), list(request.requiredCapabilities()),
                visibility(request.visibility(), old.visibility()), request.budgetMin(), request.budgetMax(),
                request.responseDeadline(), "DRAFT", null, old.version() + 1, false, Instant.now());
        demands.put(id, new StoredDemand(updated, false));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<DemandView> transitionDemand(
            UUID id, long expectedVersion, String targetStatus, String reason, ActorScope actor) {
        StoredDemand stored = demands.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        DemandView old = stored.value();
        DemandView updated = new DemandView(
                old.id(), old.enterpriseId(), old.enterpriseName(), old.title(), old.description(),
                old.scenarios(), old.requiredCapabilities(), old.visibility(), old.budgetMin(), old.budgetMax(),
                old.responseDeadline(), targetStatus, clean(reason), old.version() + 1,
                "DISABLED".equals(targetStatus), Instant.now());
        demands.put(id, new StoredDemand(updated, false));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<DemandView> softDeleteDemand(
            UUID id, long expectedVersion, ActorScope actor) {
        StoredDemand stored = demands.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        DemandView old = stored.value();
        Instant deletedAt = Instant.now();
        DemandView updated = new DemandView(
                old.id(), old.enterpriseId(), old.enterpriseName(), old.title(), old.description(),
                old.scenarios(), old.requiredCapabilities(), old.visibility(), old.budgetMin(), old.budgetMax(),
                old.responseDeadline(), old.status(), old.closeReason(), old.version() + 1,
                old.disabled(), true, deletedAt, deletedAt);
        demands.put(id, new StoredDemand(updated, true));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<DemandView> restoreDemand(
            UUID id, long expectedVersion, ActorScope actor) {
        StoredDemand stored = demands.get(id);
        if (stored == null || !stored.deleted() || stored.value().version() != expectedVersion
                || !canWrite(actor, stored.value().enterpriseId())) {
            return Optional.empty();
        }
        DemandView old = stored.value();
        DemandView updated = copyDemand(old, "DRAFT", null, old.version() + 1, false);
        demands.put(id, new StoredDemand(updated, false));
        return Optional.of(updated);
    }

    @Override
    public boolean isDemandDeleted(UUID demandId) {
        StoredDemand stored = demands.get(demandId);
        return stored == null || stored.deleted();
    }

    @Override
    public boolean isDemandOpenForResponse(UUID demandId) {
        StoredDemand stored = demands.get(demandId);
        if (stored == null || stored.deleted()) {
            return false;
        }
        DemandView demand = stored.value();
        return "OPEN".equals(demand.status())
                && !demand.disabled()
                && !"DIRECTED".equals(demand.visibility())
                && (demand.responseDeadline() == null
                || demand.responseDeadline().isAfter(Instant.now()));
    }

    @Override
    public boolean enterpriseBelongsToAssociation(UUID enterpriseId, UUID associationId) {
        return enterpriseLifecycle.isOperational(enterpriseId)
                && associationId != null
                && associationId.equals(enterpriseAssociations.get(enterpriseId));
    }

    @Override
    public boolean enterpriseHistoricallyBelongsToAssociation(UUID enterpriseId, UUID associationId) {
        return associationId != null && associationId.equals(enterpriseAssociations.get(enterpriseId));
    }

    @Override
    public void recordChange(
            ActorScope actor,
            String action,
            String resourceType,
            UUID resourceId,
            UUID associationId,
            UUID enterpriseId,
            long version,
            Object snapshot) {
        // In-memory mode is isolated for tests and demos. Durable history is provided by the PostgreSQL adapter.
    }

    private boolean canRead(
            ActorScope actor, UUID enterpriseId, String visibility, String status) {
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                return actor.enterpriseId() == null;
            }
            UUID ownerAssociationId = enterpriseAssociations.get(enterpriseId);
            return actor.associationId().equals(ownerAssociationId)
                    && (actor.enterpriseId() == null || actor.enterpriseId().equals(enterpriseId));
        }
        if (enterpriseId.equals(actor.enterpriseId())) {
            return true;
        }
        UUID ownerAssociationId = enterpriseAssociations.get(enterpriseId);
        boolean sameAssociation = ownerAssociationId != null && ownerAssociationId.equals(actor.associationId());
        if (sameAssociation && actor.isAssociationStaff()) {
            return true;
        }
        if (!"ACTIVE".equals(status) || ownerAssociationId == null) {
            return false;
        }
        if (sameAssociation) {
            return "MEMBERS".equals(visibility) || "PUBLIC".equals(visibility);
        }
        // The memory adapter has no durable share-policy or consent store. Fail closed
        // instead of treating a partner relationship as blanket resource consent.
        return false;
    }

    private static boolean canReadDeletion(
            ActorScope actor, UUID enterpriseId, boolean deleted, boolean includeDeleted) {
        if (!deleted) {
            return true;
        }
        if (!includeDeleted) {
            return false;
        }
        return actor.isSystemAdmin() || actor.isAssociationStaff()
                || actor.isEnterpriseAdmin() && enterpriseId.equals(actor.enterpriseId());
    }

    private boolean canReadEnterpriseHistory(ActorScope actor, UUID enterpriseId) {
        return actor.isSystemAdmin() || actor.isAssociationStaff()
                || enterpriseLifecycle.isOperational(enterpriseId);
    }

    private void bindEnterpriseAssociation(UUID enterpriseId, ActorScope actor) {
        if (actor.associationId() != null) {
            enterpriseAssociations.putIfAbsent(enterpriseId, actor.associationId());
        }
    }

    private void requireCreateScope(UUID enterpriseId, ActorScope actor) {
        if (!actor.isSystemAdmin()) {
            return;
        }
        EcosystemScopeGuard.requireWriteContext(actor);
        if (actor.enterpriseId() == null || !actor.enterpriseId().equals(enterpriseId)) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "catalog records must be created for the selected enterprise");
        }
    }

    private boolean canWrite(ActorScope actor, UUID enterpriseId) {
        if (!actor.isSystemAdmin()) {
            return true;
        }
        return actor.associationId() != null
                && actor.associationId().equals(enterpriseAssociations.get(enterpriseId))
                && (actor.enterpriseId() == null || actor.enterpriseId().equals(enterpriseId));
    }

    private static boolean matches(String query, String... values) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static OfferingView copyOffering(
            OfferingView old, String status, long version, boolean disabled) {
        return new OfferingView(
                old.id(), old.enterpriseId(), old.enterpriseName(), old.name(), old.kind(), old.description(),
                old.scenarios(), old.qualifications(), old.visibility(), status, version, disabled, Instant.now());
    }

    private static DemandView copyDemand(
            DemandView old, String status, String reason, long version, boolean disabled) {
        return new DemandView(
                old.id(), old.enterpriseId(), old.enterpriseName(), old.title(), old.description(),
                old.scenarios(), old.requiredCapabilities(), old.visibility(), old.budgetMin(), old.budgetMax(),
                old.responseDeadline(), status, reason, version, disabled, Instant.now());
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String visibility(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record StoredOffering(OfferingView value, boolean deleted) {
    }

    private record StoredDemand(DemandView value, boolean deleted) {
    }
}
