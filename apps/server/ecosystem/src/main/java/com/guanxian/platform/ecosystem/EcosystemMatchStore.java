package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

interface EcosystemMatchStore {
    List<PersistedMatchView> upsert(
            DemandView demand, List<MatchCandidateDraft> candidates, ActorScope actor);

    List<PersistedMatchView> list(UUID demandId, ActorScope actor);

    List<PersistedMatchView> list(ActorScope actor);

    default List<PersistedMatchView> list(
            ActorScope actor, String state, long offset, int limit) {
        try (Stream<PersistedMatchView> values = list(actor).stream()) {
            return values
                    .filter(value -> state == null || state.equals(value.state()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    default long count(ActorScope actor, String state) {
        try (Stream<PersistedMatchView> values = list(actor).stream()) {
            return values.filter(value -> state == null || state.equals(value.state())).count();
        }
    }

    Optional<PersistedMatchView> find(UUID id, ActorScope actor);

    Optional<PersistedMatchView> recommend(
            UUID id, long expectedVersion, ActorScope actor);

    Optional<PersistedMatchView> confirm(
            UUID id, long expectedVersion, UUID enterpriseId, ActorScope actor);

    Optional<PersistedMatchView> transition(
            UUID id, long expectedVersion, String targetState, String closeReason, ActorScope actor);
}
