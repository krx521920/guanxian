package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EcosystemMatchStore {
    List<PersistedMatchView> upsert(
            DemandView demand, List<MatchCandidateDraft> candidates, ActorScope actor);

    List<PersistedMatchView> list(UUID demandId, ActorScope actor);

    List<PersistedMatchView> list(ActorScope actor);

    Optional<PersistedMatchView> find(UUID id, ActorScope actor);

    Optional<PersistedMatchView> recommend(
            UUID id, long expectedVersion, ActorScope actor);

    Optional<PersistedMatchView> confirm(
            UUID id, long expectedVersion, UUID enterpriseId, ActorScope actor);

    Optional<PersistedMatchView> transition(
            UUID id, long expectedVersion, String targetState, String closeReason, ActorScope actor);
}
