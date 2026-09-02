package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CollaborationStore {
    default List<CollaborationView> list(
            ActorScope actor, String query, boolean includeDeleted, long offset, int limit) {
        return list(actor, query, null, includeDeleted, offset, limit);
    }

    List<CollaborationView> list(
            ActorScope actor, String query, String stage,
            boolean includeDeleted, long offset, int limit);

    default long count(ActorScope actor, String query, boolean includeDeleted) {
        return count(actor, query, null, includeDeleted);
    }

    long count(ActorScope actor, String query, String stage, boolean includeDeleted);

    Optional<CollaborationView> find(UUID id, ActorScope actor, boolean includeDeleted);

    boolean canLinkMatch(UUID matchId, UUID associationId, UUID enterpriseId);

    boolean canAccessLinkedMatch(UUID matchId, UUID associationId, UUID enterpriseId);

    default boolean linkedMatchParticipantsOperational(UUID matchId) {
        return true;
    }

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
