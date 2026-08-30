package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryEcosystemMatchStore implements EcosystemMatchStore {
    private final ConcurrentMap<UUID, PersistedMatchView> matches = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> matchAssociations = new ConcurrentHashMap<>();
    private final EnterpriseLifecycle enterpriseLifecycle;
    private final EcosystemCatalogStore catalogStore;

    @Autowired
    InMemoryEcosystemMatchStore(
            EnterpriseLifecycle enterpriseLifecycle,
            EcosystemCatalogStore catalogStore) {
        this.enterpriseLifecycle = enterpriseLifecycle;
        this.catalogStore = catalogStore;
    }

    InMemoryEcosystemMatchStore(EnterpriseLifecycle enterpriseLifecycle) {
        this(enterpriseLifecycle, null);
    }

    InMemoryEcosystemMatchStore() {
        this(enterpriseId -> true, null);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(new HashMap<>(matches), new HashMap<>(matchAssociations));
    }

    synchronized void restore(Snapshot snapshot) {
        matches.clear();
        matches.putAll(snapshot.matches());
        matchAssociations.clear();
        matchAssociations.putAll(snapshot.matchAssociations());
    }

    @Override
    public synchronized List<PersistedMatchView> upsert(
            DemandView demand, List<MatchCandidateDraft> candidates, ActorScope actor) {
        requireSystemUpsertScope(demand, actor);
        for (MatchCandidateDraft candidate : candidates) {
            requireDistinctEnterprises(demand.enterpriseId(), candidate.candidateEnterpriseId());
            UUID id = UUID.nameUUIDFromBytes(
                    (demand.id() + ":" + candidate.candidateEnterpriseId()).getBytes(StandardCharsets.UTF_8));
            PersistedMatchView existing = matches.get(id);
            if (existing != null
                    && !MatchLifecycle.PENDING_CONFIRMATION.equals(existing.state())) {
                continue;
            }
            PersistedMatchView value = new PersistedMatchView(
                    id,
                    demand.id(),
                    demand.enterpriseId(),
                    candidate.candidateEnterpriseId(),
                    demand.enterpriseName(),
                    demand.title(),
                    demand.scenarios().isEmpty() ? null : demand.scenarios().getFirst(),
                    candidate.supplierCompany(),
                    candidate.solution(),
                    candidate.score(),
                    candidate.reasons(),
                    existing == null ? MatchLifecycle.PENDING_CONFIRMATION : existing.state(),
                    existing == null ? null : existing.recommendedAt(),
                    existing == null ? null : existing.demandConfirmedAt(),
                    existing == null ? null : existing.candidateConfirmedAt(),
                    existing == null ? null : existing.closedReason(),
                    existing == null ? 0 : existing.version() + 1,
                    Instant.now(), Set.of());
            matches.put(id, value);
            if (actor.associationId() != null) {
                matchAssociations.putIfAbsent(id, actor.associationId());
            }
        }
        return list(demand.id(), actor);
    }

    @Override
    public List<PersistedMatchView> list(UUID demandId, ActorScope actor) {
        return matches.values().stream()
                .filter(value -> value.demandId().equals(demandId))
                .filter(value -> canRead(value, actor))
                .sorted(Comparator.comparingInt(PersistedMatchView::score).reversed()
                        .thenComparing(PersistedMatchView::supplierCompany)
                        .thenComparing(PersistedMatchView::id))
                .toList();
    }

    @Override
    public List<PersistedMatchView> list(ActorScope actor) {
        return matches.values().stream()
                .filter(value -> canRead(value, actor))
                .sorted(Comparator.comparing(PersistedMatchView::updatedAt).reversed()
                        .thenComparing(Comparator.comparingInt(PersistedMatchView::score).reversed())
                        .thenComparing(PersistedMatchView::id))
                .toList();
    }

    @Override
    public Optional<PersistedMatchView> find(UUID id, ActorScope actor) {
        PersistedMatchView value = matches.get(id);
        return value != null && canRead(value, actor) ? Optional.of(value) : Optional.empty();
    }

    @Override
    public synchronized Optional<PersistedMatchView> recommend(
            UUID id, long expectedVersion, ActorScope actor) {
        PersistedMatchView old = matches.get(id);
        if (old == null || old.version() != expectedVersion || old.recommendedAt() != null
                || !MatchLifecycle.PENDING_CONFIRMATION.equals(old.state())
                || !canWrite(old, actor)) {
            return Optional.empty();
        }
        String state = MatchLifecycle.PENDING_CONFIRMATION.equals(old.state())
                ? MatchLifecycle.RECOMMENDED : old.state();
        PersistedMatchView updated = copy(old, state, Instant.now(), old.demandConfirmedAt(),
                old.candidateConfirmedAt(), null);
        matches.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<PersistedMatchView> confirm(
            UUID id, long expectedVersion, UUID enterpriseId, ActorScope actor) {
        PersistedMatchView old = matches.get(id);
        if (old == null || old.version() != expectedVersion || !canWrite(old, actor)
                || !List.of(MatchLifecycle.RECOMMENDED,
                MatchLifecycle.PARTIALLY_CONFIRMED).contains(old.state())
                || old.recommendedAt() == null) {
            return Optional.empty();
        }
        if (actor.enterpriseId() == null || !actor.enterpriseId().equals(enterpriseId)) {
            return Optional.empty();
        }
        boolean demand = enterpriseId.equals(old.demandEnterpriseId());
        boolean candidate = enterpriseId.equals(old.candidateEnterpriseId());
        if ((!demand && !candidate)
                || demand && old.demandConfirmedAt() != null
                || candidate && old.candidateConfirmedAt() != null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant demandAt = demand ? now : old.demandConfirmedAt();
        Instant candidateAt = candidate ? now : old.candidateConfirmedAt();
        String state = demandAt != null && candidateAt != null
                ? MatchLifecycle.CONFIRMED : MatchLifecycle.PARTIALLY_CONFIRMED;
        PersistedMatchView updated = copy(
                old, state, old.recommendedAt(), demandAt, candidateAt, null);
        matches.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<PersistedMatchView> transition(
            UUID id, long expectedVersion, String targetState, String closeReason, ActorScope actor) {
        PersistedMatchView old = matches.get(id);
        if (old == null || old.version() != expectedVersion || !canWrite(old, actor)) {
            return Optional.empty();
        }
        PersistedMatchView updated = new PersistedMatchView(
                old.id(), old.demandId(), old.demandEnterpriseId(), old.candidateEnterpriseId(),
                old.demandCompany(), old.demandTitle(), old.scene(), old.supplierCompany(),
                old.solution(), old.score(), old.reasons(), targetState,
                old.recommendedAt(), old.demandConfirmedAt(), old.candidateConfirmedAt(), closeReason,
                old.version() + 1, Instant.now(), Set.of());
        matches.put(id, updated);
        return Optional.of(updated);
    }

    private static PersistedMatchView copy(
            PersistedMatchView old,
            String state,
            Instant recommendedAt,
            Instant demandConfirmedAt,
            Instant candidateConfirmedAt,
            String closeReason) {
        return new PersistedMatchView(
                old.id(), old.demandId(), old.demandEnterpriseId(), old.candidateEnterpriseId(),
                old.demandCompany(), old.demandTitle(), old.scene(), old.supplierCompany(),
                old.solution(), old.score(), old.reasons(), state, recommendedAt,
                demandConfirmedAt, candidateConfirmedAt, closeReason,
                old.version() + 1, Instant.now(), Set.of());
    }

    private boolean canRead(PersistedMatchView value, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            if (actor.associationId() == null) {
                return actor.enterpriseId() == null;
            }
            if (actor.enterpriseId() != null) {
                return catalogStore != null
                        && catalogStore.enterpriseBelongsToAssociation(
                        actor.enterpriseId(), actor.associationId())
                        && (value.demandEnterpriseId().equals(actor.enterpriseId())
                        || value.candidateEnterpriseId().equals(actor.enterpriseId())
                        && !MatchLifecycle.PENDING_CONFIRMATION.equals(value.state()));
            }
            return actor.associationId().equals(matchAssociations.get(value.id()))
                    || catalogStore != null && catalogStore.enterpriseBelongsToAssociation(
                    value.candidateEnterpriseId(), actor.associationId())
                    && !MatchLifecycle.PENDING_CONFIRMATION.equals(value.state());
        }
        if (actor.isAssociationStaff()) {
            return actor.associationId() != null
                    && (actor.associationId().equals(matchAssociations.get(value.id()))
                    || !MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                    && canReadAsPartner(value, actor));
        }
        return isOperational(value)
                && (value.demandEnterpriseId().equals(actor.enterpriseId())
                || value.candidateEnterpriseId().equals(actor.enterpriseId())
                && !MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                || !MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                && canReadAsPartner(value, actor));
    }

    private boolean canReadAsPartner(PersistedMatchView value, ActorScope actor) {
        if (catalogStore == null || actor.associationId() == null) {
            return false;
        }
        return ownerAssociationIsReachable(value.demandEnterpriseId(), actor)
                && ownerAssociationIsReachable(value.candidateEnterpriseId(), actor);
    }

    private boolean ownerAssociationIsReachable(UUID enterpriseId, ActorScope actor) {
        return catalogStore.enterpriseBelongsToAssociation(enterpriseId, actor.associationId())
                || actor.partnerAssociationIds().stream().anyMatch(
                partnerId -> catalogStore.enterpriseBelongsToAssociation(enterpriseId, partnerId));
    }

    private boolean canWrite(PersistedMatchView value, ActorScope actor) {
        return isOperational(value)
                && (!actor.isSystemAdmin() || actor.associationId() != null)
                && canRead(value, actor);
    }

    private boolean isOperational(PersistedMatchView value) {
        return !value.demandEnterpriseId().equals(value.candidateEnterpriseId())
                && enterpriseLifecycle.isOperational(value.demandEnterpriseId())
                && enterpriseLifecycle.isOperational(value.candidateEnterpriseId());
    }

    private static void requireDistinctEnterprises(
            UUID demandEnterpriseId, UUID candidateEnterpriseId) {
        if (demandEnterpriseId.equals(candidateEnterpriseId)) {
            throw new com.guanxian.platform.shared.error.PreconditionFailedException(
                    "a demand enterprise cannot be matched with itself");
        }
    }

    private void requireSystemUpsertScope(DemandView demand, ActorScope actor) {
        if (!actor.isSystemAdmin()) {
            return;
        }
        EcosystemScopeGuard.requireWriteContext(actor);
        if (actor.enterpriseId() != null
                && !actor.enterpriseId().equals(demand.enterpriseId())) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "matches must be generated for the selected enterprise's demand");
        }
        if (catalogStore == null || !catalogStore.enterpriseBelongsToAssociation(
                demand.enterpriseId(), actor.associationId())) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "demand is outside the selected association context");
        }
    }

    record Snapshot(
            Map<UUID, PersistedMatchView> matches,
            Map<UUID, UUID> matchAssociations) {
    }
}
