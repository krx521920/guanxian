package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CollaborationStore {
    List<CollaborationView> list(
            ActorScope actor, String query, boolean includeDeleted, int offset, int limit);

    long count(ActorScope actor, String query, boolean includeDeleted);

    Optional<CollaborationView> find(UUID id, ActorScope actor, boolean includeDeleted);

    CollaborationView create(
            UUID associationId,
            UUID enterpriseId,
            CollaborationUpsertRequest request,
            ActorScope actor);

    Optional<CollaborationView> update(
            UUID id,
            long expectedVersion,
            CollaborationUpsertRequest request,
            ActorScope actor);

    Optional<CollaborationView> transition(
            UUID id,
            long expectedVersion,
            String stage,
            boolean disabled,
            ActorScope actor);

    Optional<CollaborationView> softDelete(UUID id, long expectedVersion, ActorScope actor);

    Optional<CollaborationView> restore(UUID id, long expectedVersion, ActorScope actor);

    CollaborationActivityView appendActivity(
            UUID collaborationId, String type, String detail, ActorScope actor);

    List<CollaborationActivityView> activities(UUID collaborationId, int limit);

    List<CollaborationHistoryView> history(UUID collaborationId, int limit);

    void recordChange(ActorScope actor, String action, CollaborationView value, String detail);
}
