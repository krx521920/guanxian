package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;
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

    @Override
    public List<OfferingView> listOfferings(
            ActorScope actor, String query, boolean includeDeleted, int offset, int limit) {
        return offerings.values().stream()
                .filter(item -> includeDeleted || !item.deleted())
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
                .filter(item -> includeDeleted || !item.deleted())
                .map(StoredOffering::value)
                .filter(item -> canRead(actor, item.enterpriseId(), item.visibility(), item.status()))
                .filter(item -> matches(query, item.name(), item.description(), item.enterpriseName()))
                .count();
    }

    @Override
    public Optional<OfferingView> findOffering(UUID id, ActorScope actor, boolean includeDeleted) {
        StoredOffering item = offerings.get(id);
        if (item == null || (!includeDeleted && item.deleted())
                || !canRead(actor, item.value().enterpriseId(), item.value().visibility(), item.value().status())) {
            return Optional.empty();
        }
        return Optional.of(item.value());
    }

    @Override
    public synchronized OfferingView createOffering(
            UUID enterpriseId, OfferingUpsertRequest request, ActorScope actor) {
        UUID id = UUID.randomUUID();
        OfferingView value = new OfferingView(
                id, enterpriseId, null, request.name().trim(), request.kind(),
                clean(request.description()), list(request.scenarios()), list(request.qualifications()),
                visibility(request.visibility(), "MEMBERS"), "DRAFT", 0, false, Instant.now());
        offerings.put(id, new StoredOffering(value, false));
        return value;
    }

    @Override
    public synchronized Optional<OfferingView> updateOffering(
            UUID id, long expectedVersion, OfferingUpsertRequest request, ActorScope actor) {
        StoredOffering stored = offerings.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion) {
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
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion) {
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
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion) {
            return Optional.empty();
        }
        OfferingView old = stored.value();
        OfferingView updated = copyOffering(old, old.status(), old.version() + 1, old.disabled());
        offerings.put(id, new StoredOffering(updated, true));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<OfferingView> restoreOffering(
            UUID id, long expectedVersion, ActorScope actor) {
        StoredOffering stored = offerings.get(id);
        if (stored == null || !stored.deleted() || stored.value().version() != expectedVersion) {
            return Optional.empty();
        }
        OfferingView old = stored.value();
        OfferingView updated = copyOffering(old, "DRAFT", old.version() + 1, false);
        offerings.put(id, new StoredOffering(updated, false));
        return Optional.of(updated);
    }

    @Override
    public List<DemandView> listDemands(
            ActorScope actor, String query, boolean includeDeleted, int offset, int limit) {
        return demands.values().stream()
                .filter(item -> includeDeleted || !item.deleted())
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
                .filter(item -> includeDeleted || !item.deleted())
                .map(StoredDemand::value)
                .filter(item -> canRead(actor, item.enterpriseId(), item.visibility(), item.status()))
                .filter(item -> matches(query, item.title(), item.description(), item.enterpriseName()))
                .count();
    }

    @Override
    public Optional<DemandView> findDemand(UUID id, ActorScope actor, boolean includeDeleted) {
        StoredDemand item = demands.get(id);
        if (item == null || (!includeDeleted && item.deleted())
                || !canRead(actor, item.value().enterpriseId(), item.value().visibility(), item.value().status())) {
            return Optional.empty();
        }
        return Optional.of(item.value());
    }

    @Override
    public synchronized DemandView createDemand(
            UUID enterpriseId, DemandUpsertRequest request, ActorScope actor) {
        UUID id = UUID.randomUUID();
        DemandView value = new DemandView(
                id, enterpriseId, null, request.title().trim(), request.description().trim(),
                list(request.scenarios()), list(request.requiredCapabilities()),
                visibility(request.visibility(), "DIRECTED"), request.budgetMin(), request.budgetMax(),
                request.responseDeadline(), "DRAFT", null, 0, false, Instant.now());
        demands.put(id, new StoredDemand(value, false));
        return value;
    }

    @Override
    public synchronized Optional<DemandView> updateDemand(
            UUID id, long expectedVersion, DemandUpsertRequest request, ActorScope actor) {
        StoredDemand stored = demands.get(id);
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion) {
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
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion) {
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
        if (stored == null || stored.deleted() || stored.value().version() != expectedVersion) {
            return Optional.empty();
        }
        DemandView old = stored.value();
        DemandView updated = copyDemand(old, old.status(), old.closeReason(), old.version() + 1, old.disabled());
        demands.put(id, new StoredDemand(updated, true));
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<DemandView> restoreDemand(
            UUID id, long expectedVersion, ActorScope actor) {
        StoredDemand stored = demands.get(id);
        if (stored == null || !stored.deleted() || stored.value().version() != expectedVersion) {
            return Optional.empty();
        }
        DemandView old = stored.value();
        DemandView updated = copyDemand(old, "DRAFT", null, old.version() + 1, false);
        demands.put(id, new StoredDemand(updated, false));
        return Optional.of(updated);
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

    private static boolean canRead(
            ActorScope actor, UUID enterpriseId, String visibility, String status) {
        if (actor.isSystemAdmin() || actor.isAssociationStaff() || enterpriseId.equals(actor.enterpriseId())) {
            return true;
        }
        return "ACTIVE".equals(status) && ("PUBLIC".equals(visibility) || "MEMBERS".equals(visibility)
                || "PARTNERS".equals(visibility));
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
