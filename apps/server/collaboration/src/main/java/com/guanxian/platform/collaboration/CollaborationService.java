package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CollaborationService {
    private static final Set<String> EDITABLE = Set.of("DRAFT", "REJECTED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS"),
            "IN_PROGRESS", Set.of("OPEN", "COMPLETED"),
            "COMPLETED", Set.of("OPEN"));

    private final CollaborationStore store;
    private final ActorScopeResolver actorScopeResolver;

    public CollaborationService(CollaborationStore store, ActorScopeResolver actorScopeResolver) {
        this.store = store;
        this.actorScopeResolver = actorScopeResolver;
    }

    public List<CollaborationView> findAll() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return List.of();
        }
        return findAll(actorScopeResolver.resolve(authentication));
    }

    @Transactional(readOnly = true)
    public List<CollaborationView> findAll(ActorScope actor) {
        return store.list(actor, null, false, 0, 100);
    }

    @Transactional(readOnly = true)
    public CollaborationPage<CollaborationView> page(
            ActorScope actor, String query, boolean includeDeleted, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        boolean allowedDeleted = includeDeleted && canManageDeleted(actor);
        return new CollaborationPage<>(
                store.list(actor, query, allowedDeleted, safePage * safeSize, safeSize),
                store.count(actor, query, allowedDeleted), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public CollaborationView get(UUID id, ActorScope actor, boolean includeDeleted) {
        boolean allowedDeleted = includeDeleted && canManageDeleted(actor);
        return store.find(id, actor, allowedDeleted)
                .orElseThrow(() -> new NotFoundException("collaboration", id));
    }

    @Transactional
    public CollaborationView create(CollaborationUpsertRequest request, ActorScope actor) {
        UUID associationId = requireAssociation(actor);
        UUID enterpriseId;
        if (actor.isSystemAdmin() || actor.isAssociationStaff()) {
            enterpriseId = null;
        } else if (actor.isEnterpriseAdmin() && actor.enterpriseId() != null) {
            enterpriseId = actor.enterpriseId();
        } else {
            throw scopeViolation();
        }
        requireMatchLink(request.matchId(), associationId, enterpriseId);
        CollaborationView created = store.create(associationId, enterpriseId, request, actor);
        store.recordChange(actor, "CREATE", created, null);
        return created;
    }

    @Transactional
    public CollaborationView update(
            UUID id, long expectedVersion, CollaborationUpsertRequest request, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current);
        requireState(current.stage(), EDITABLE, "collaboration must be DRAFT or REJECTED to edit");
        requireVersion(current.version(), expectedVersion);
        requireMatchLink(request.matchId(), current.associationId(), current.enterpriseId());
        CollaborationView updated = store.update(id, expectedVersion, request, actor)
                .orElseThrow(CollaborationService::stale);
        store.recordChange(actor, "UPDATE", updated, null);
        return updated;
    }

    @Transactional
    public CollaborationView submit(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current);
        requireState(current.stage(), EDITABLE, "only a draft or rejected collaboration can be submitted");
        return transition(current, expectedVersion, "PENDING_REVIEW", false, "SUBMIT", null, actor);
    }

    @Transactional
    public CollaborationView review(
            UUID id,
            long expectedVersion,
            CollaborationReviewRequest request,
            ActorScope actor) {
        requireReviewer(actor);
        CollaborationView current = get(id, actor, false);
        requireState(current.stage(), Set.of("PENDING_REVIEW"), "collaboration is not pending review");
        String target = request.approved() ? "OPEN" : "REJECTED";
        String action = request.approved() ? "APPROVE" : "REJECT";
        return transition(current, expectedVersion, target, false, action, request.comment(), actor);
    }

    @Transactional
    public CollaborationView advance(
            UUID id,
            long expectedVersion,
            CollaborationTransitionRequest request,
            ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current);
        String target = request.targetStage().trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = TRANSITIONS.getOrDefault(current.stage(), Set.of());
        if (!allowed.contains(target)) {
            throw new PreconditionFailedException(
                    "invalid collaboration transition from " + current.stage() + " to " + target);
        }
        return transition(current, expectedVersion, target, false, "TRANSITION", request.detail(), actor);
    }

    @Transactional
    public CollaborationView disable(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current);
        if (current.disabled() || "DISABLED".equals(current.stage())) {
            throw new PreconditionFailedException("collaboration is already disabled");
        }
        return transition(current, expectedVersion, "DISABLED", true, "DISABLE", null, actor);
    }

    @Transactional
    public CollaborationView delete(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current);
        requireVersion(current.version(), expectedVersion);
        CollaborationView deleted = store.softDelete(id, expectedVersion, actor)
                .orElseThrow(CollaborationService::stale);
        store.recordChange(actor, "SOFT_DELETE", deleted, null);
        return deleted;
    }

    @Transactional
    public CollaborationView restore(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, true);
        requireManager(actor, current);
        requireVersion(current.version(), expectedVersion);
        CollaborationView restored;
        if (current.deleted()) {
            restored = store.restore(id, expectedVersion, actor)
                    .orElseThrow(CollaborationService::stale);
        } else if (current.disabled() || "DISABLED".equals(current.stage())) {
            restored = store.transition(id, expectedVersion, "DRAFT", false, actor)
                    .orElseThrow(CollaborationService::stale);
        } else {
            throw new PreconditionFailedException("collaboration is neither deleted nor disabled");
        }
        store.recordChange(actor, "RESTORE", restored, null);
        return restored;
    }

    @Transactional(readOnly = true)
    public List<CollaborationActivityView> activities(
            UUID id, int limit, ActorScope actor) {
        get(id, actor, true);
        return store.activities(id, safeLimit(limit));
    }

    @Transactional
    public CollaborationActivityView addActivity(
            UUID id, CollaborationActivityRequest request, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current);
        return store.appendActivity(
                id,
                request.type().trim().toUpperCase(Locale.ROOT),
                request.detail().trim(),
                actor);
    }

    @Transactional(readOnly = true)
    public List<CollaborationHistoryView> history(
            UUID id, int limit, ActorScope actor) {
        get(id, actor, true);
        return store.history(id, safeLimit(limit));
    }

    private CollaborationView transition(
            CollaborationView current,
            long expectedVersion,
            String stage,
            boolean disabled,
            String action,
            String detail,
            ActorScope actor) {
        requireVersion(current.version(), expectedVersion);
        CollaborationView updated = store.transition(
                current.id(), expectedVersion, stage, disabled, actor)
                .orElseThrow(CollaborationService::stale);
        store.recordChange(actor, action, updated, detail);
        return updated;
    }

    private static boolean canManageDeleted(ActorScope actor) {
        return actor.isSystemAdmin() || actor.isAssociationStaff() || actor.isEnterpriseAdmin();
    }

    private static UUID requireAssociation(ActorScope actor) {
        if (actor.associationId() == null) {
            throw new ForbiddenException(
                    "ASSOCIATION_CONTEXT_REQUIRED", "an association identity is required");
        }
        return actor.associationId();
    }

    private void requireManager(ActorScope actor, CollaborationView item) {
        if (actor.isSystemAdmin()) {
            return;
        }
        if (actor.isAssociationStaff() && item.associationId().equals(actor.associationId())) {
            return;
        }
        if (actor.isEnterpriseAdmin()
                && actor.enterpriseId() != null
                && (actor.enterpriseId().equals(item.enterpriseId())
                || item.matchId() != null && store.canLinkMatch(
                        item.matchId(), item.associationId(), actor.enterpriseId()))) {
            return;
        }
        throw scopeViolation();
    }

    private void requireMatchLink(UUID matchId, UUID associationId, UUID enterpriseId) {
        if (matchId != null && !store.canLinkMatch(matchId, associationId, enterpriseId)) {
            throw new ForbiddenException(
                    "MATCH_LINK_SCOPE_VIOLATION",
                    "the linked match is outside the collaboration data scope");
        }
    }

    private static void requireReviewer(ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            throw new ForbiddenException(
                    "REVIEWER_REQUIRED", "association reviewer identity is required");
        }
    }

    private static void requireState(String actual, Set<String> expected, String message) {
        if (!expected.contains(actual)) {
            throw new PreconditionFailedException(message);
        }
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static int safeLimit(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }

    private static ForbiddenException scopeViolation() {
        return new ForbiddenException(
                "COLLABORATION_SCOPE_VIOLATION",
                "enterprise can only manage its own collaboration items");
    }

    private static PreconditionFailedException stale() {
        return new PreconditionFailedException(
                "resource version is stale; reload and retry with the latest ETag");
    }
}
