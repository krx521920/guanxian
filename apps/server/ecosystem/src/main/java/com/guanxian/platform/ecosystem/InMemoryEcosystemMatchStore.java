package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryEcosystemMatchStore implements EcosystemMatchStore {
    private final ConcurrentMap<UUID, PersistedMatchView> matches = new ConcurrentHashMap<>();

    @Override
    public synchronized List<PersistedMatchView> upsert(
            DemandView demand, List<MatchCandidateDraft> candidates, ActorScope actor) {
        for (MatchCandidateDraft candidate : candidates) {
            UUID id = UUID.nameUUIDFromBytes(
                    (demand.id() + ":" + candidate.candidateEnterpriseId()).getBytes(StandardCharsets.UTF_8));
            PersistedMatchView existing = matches.get(id);
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
                    existing == null ? "PENDING_CONFIRMATION" : existing.state(),
                    existing == null ? null : existing.closedReason(),
                    existing == null ? 0 : existing.version() + 1,
                    Instant.now());
            matches.put(id, value);
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
    public Optional<PersistedMatchView> find(UUID id, ActorScope actor) {
        PersistedMatchView value = matches.get(id);
        return value != null && canRead(value, actor) ? Optional.of(value) : Optional.empty();
    }

    @Override
    public synchronized Optional<PersistedMatchView> transition(
            UUID id, long expectedVersion, String targetState, String closeReason, ActorScope actor) {
        PersistedMatchView old = matches.get(id);
        if (old == null || old.version() != expectedVersion || !canRead(old, actor)) {
            return Optional.empty();
        }
        PersistedMatchView updated = new PersistedMatchView(
                old.id(), old.demandId(), old.demandEnterpriseId(), old.candidateEnterpriseId(),
                old.demandCompany(), old.demandTitle(), old.scene(), old.supplierCompany(),
                old.solution(), old.score(), old.reasons(), targetState, closeReason,
                old.version() + 1, Instant.now());
        matches.put(id, updated);
        return Optional.of(updated);
    }

    private static boolean canRead(PersistedMatchView value, ActorScope actor) {
        return actor.isSystemAdmin() || actor.isAssociationStaff()
                || value.demandEnterpriseId().equals(actor.enterpriseId())
                || value.candidateEnterpriseId().equals(actor.enterpriseId());
    }
}
