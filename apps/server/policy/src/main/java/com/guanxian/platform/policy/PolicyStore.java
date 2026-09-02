package com.guanxian.platform.policy;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PolicyStore {
    List<PolicyView> list(
            ActorScope actor, String query, String level,
            boolean includeDeleted, int offset, int limit);
    long count(ActorScope actor, String query, String level, boolean includeDeleted);
    List<String> levels(ActorScope actor);
    Optional<PolicyView> find(UUID id, ActorScope actor, boolean includeDeleted);
    PolicyView create(UUID associationId, PolicyUpsertRequest request, ActorScope actor);
    Optional<PolicyView> update(UUID id, long expectedVersion, PolicyUpsertRequest request, ActorScope actor);
    Optional<PolicyView> transition(UUID id, long expectedVersion, String targetStatus, ActorScope actor);
    Optional<PolicyView> softDelete(UUID id, long expectedVersion, ActorScope actor);
    Optional<PolicyView> restore(UUID id, long expectedVersion, ActorScope actor);
    void recordChange(ActorScope actor, String action, PolicyView policy, String comment);
    List<PolicyHistoryView> history(UUID id, ActorScope actor, int limit);
}
