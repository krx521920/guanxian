package com.guanxian.platform.collaboration;

import com.guanxian.platform.member.api.EnterpriseLifecycle;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import com.guanxian.platform.shared.notification.BusinessNotification;
import com.guanxian.platform.shared.notification.BusinessNotificationPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CollaborationService {
    private static final Set<String> EDITABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> MAINTAINABLE = Set.of("OPEN", "IN_PROGRESS");
    private static final Set<String> UPDATABLE = Set.of(
            "DRAFT", "REJECTED", "OPEN", "IN_PROGRESS");
    private static final Set<String> FILTER_STAGES = Set.of(
            "ACTIVE", "DRAFT", "PENDING_REVIEW", "REJECTED", "OPEN",
            "IN_PROGRESS", "COMPLETED", "DISABLED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS"),
            "IN_PROGRESS", Set.of("OPEN", "COMPLETED"),
            "COMPLETED", Set.of("OPEN"));

    private final CollaborationStore store;
    private final ActorScopeResolver actorScopeResolver;
    private final EnterpriseLifecycle enterpriseLifecycle;
    private final BusinessNotificationPublisher notifications;

    @Autowired
    public CollaborationService(
            CollaborationStore store,
            ActorScopeResolver actorScopeResolver,
            EnterpriseLifecycle enterpriseLifecycle,
            BusinessNotificationPublisher notifications) {
        this.store = store;
        this.actorScopeResolver = actorScopeResolver;
        this.enterpriseLifecycle = enterpriseLifecycle;
        this.notifications = notifications;
    }

    public CollaborationService(
            CollaborationStore store,
            ActorScopeResolver actorScopeResolver,
            EnterpriseLifecycle enterpriseLifecycle) {
        this(store, actorScopeResolver, enterpriseLifecycle, (event, actor) -> 0);
    }

    CollaborationService(CollaborationStore store, ActorScopeResolver actorScopeResolver) {
        this(store, actorScopeResolver, enterpriseId -> true);
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
        return page(actor, query, null, includeDeleted, page, size);
    }

    @Transactional(readOnly = true)
    public CollaborationPage<CollaborationView> page(
            ActorScope actor, String query, String requestedStage,
            boolean includeDeleted, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) safePage * safeSize;
        boolean allowedDeleted = includeDeleted && canManageDeleted(actor);
        String stage = normalizedStage(requestedStage);
        return new CollaborationPage<>(
                store.list(actor, query, stage, allowedDeleted, offset, safeSize),
                store.count(actor, query, stage, allowedDeleted), safePage, safeSize);
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
        if (actor.isSystemAdmin()) {
            enterpriseId = actor.enterpriseId();
            if (enterpriseId != null) {
                requireOperational(enterpriseId);
            }
        } else if (actor.isAssociationStaff()) {
            enterpriseId = null;
        } else if (actor.isEnterpriseAdmin() && actor.enterpriseId() != null) {
            enterpriseId = actor.enterpriseId();
            requireOperational(enterpriseId);
        } else {
            throw scopeViolation();
        }
        requireMatchLink(request.matchId(), associationId, enterpriseId);
        CollaborationView created = store.create(associationId, enterpriseId, request, actor);
        store.recordChange(actor, "CREATE", created, null);
        notifyChange("CREATE", created, actor);
        return created;
    }

    @Transactional
    public CollaborationView update(
            UUID id, long expectedVersion, CollaborationUpsertRequest request, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current, false);
        requireState(current.stage(), UPDATABLE,
                "collaboration must be editable or actively progressing");
        requireVersion(current.version(), expectedVersion);
        if (MAINTAINABLE.contains(current.stage())) {
            requireActiveFieldsOnly(current, request);
        } else if (!Objects.equals(current.matchId(), request.matchId())) {
            requireMatchLink(request.matchId(), current.associationId(), current.enterpriseId());
        }
        CollaborationView updated = store.update(id, expectedVersion, request, actor)
                .orElseThrow(CollaborationService::stale);
        store.recordChange(actor, "UPDATE", updated, null);
        notifyChange("UPDATE", updated, actor);
        return updated;
    }

    @Transactional
    public CollaborationView submit(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current, false);
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
        requireReviewScope(actor, current);
        requireExistingParticipation(current, actor, false);
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
        String target = request.targetStage().trim().toUpperCase(Locale.ROOT);
        requireManager(actor, current, "COMPLETED".equals(target));
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
        requireManager(actor, current, true);
        if (current.disabled() || "DISABLED".equals(current.stage())) {
            throw new PreconditionFailedException("collaboration is already disabled");
        }
        return transition(current, expectedVersion, "DISABLED", true, "DISABLE", null, actor);
    }

    @Transactional
    public CollaborationView delete(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, false);
        requireManager(actor, current, true);
        requireVersion(current.version(), expectedVersion);
        CollaborationView deleted = store.softDelete(id, expectedVersion, actor)
                .orElseThrow(CollaborationService::stale);
        store.recordChange(actor, "SOFT_DELETE", deleted, null);
        notifyChange("SOFT_DELETE", deleted, actor);
        return deleted;
    }

    @Transactional
    public CollaborationView restore(UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = get(id, actor, true);
        requireManager(actor, current, false);
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
        notifyChange("RESTORE", restored, actor);
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
        requireManager(actor, current, false);
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
        notifyChange(action, updated, actor);
        return updated;
    }

    private void notifyChange(String action, CollaborationView value, ActorScope actor) {
        List<UUID> enterprises = value.enterpriseId() == null ? List.of() : List.of(value.enterpriseId());
        notifications.publish(new BusinessNotification(
                value.associationId(), enterprises, true,
                "COLLABORATION_CHANGED", "协作事项发生变更",
                value.title() + "：" + action + "，当前阶段 " + value.stage(),
                "COLLABORATION", value.id(), value.version(),
                "collaboration:" + value.id() + ":" + value.version() + ":" + action), actor);
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

    private void requireManager(
            ActorScope actor, CollaborationView item, boolean allowInactiveAdministratorCleanup) {
        requireExistingParticipation(item, actor, allowInactiveAdministratorCleanup);
        if (actor.isSystemAdmin()) {
            UUID associationId = requireAssociation(actor);
            if (actor.enterpriseId() != null) {
                if (item.matchId() != null && store.canAccessLinkedMatch(
                        item.matchId(), associationId, actor.enterpriseId())) {
                    return;
                }
                if (associationId.equals(item.associationId())
                        && actor.enterpriseId().equals(item.enterpriseId())) {
                    return;
                }
                throw scopeViolation();
            }
            if (associationId.equals(item.associationId())
                    || item.matchId() != null
                    && store.canAccessLinkedMatch(item.matchId(), associationId, null)) {
                return;
            }
            throw scopeViolation();
        }
        if (actor.isAssociationStaff()) {
            if (item.associationId().equals(actor.associationId())
                    || item.matchId() != null
                    && store.canAccessLinkedMatch(item.matchId(), actor.associationId(), null)) {
                return;
            }
            throw scopeViolation();
        }
        if (actor.isEnterpriseAdmin()
                && actor.enterpriseId() != null
                && (actor.enterpriseId().equals(item.enterpriseId())
                || item.matchId() != null && store.canAccessLinkedMatch(
                        item.matchId(), actor.associationId(), actor.enterpriseId()))) {
            return;
        }
        throw scopeViolation();
    }

    private static void requireReviewScope(ActorScope actor, CollaborationView item) {
        if (actor.associationId() == null || !actor.associationId().equals(item.associationId())) {
            throw new ForbiddenException(
                    "COLLABORATION_REVIEW_SCOPE_VIOLATION",
                    "only the collaboration owning association can review it");
        }
    }

    private void requireExistingParticipation(
            CollaborationView item, ActorScope actor, boolean allowInactiveAdministratorCleanup) {
        boolean administratorCleanup = allowInactiveAdministratorCleanup
                && (actor.isSystemAdmin() || actor.isAssociationStaff());
        if (!administratorCleanup && item.enterpriseId() != null) {
            requireOperational(item.enterpriseId());
        }
        if (!administratorCleanup && !actor.isSystemAdmin() && !actor.isAssociationStaff()
                && actor.enterpriseId() != null) {
            requireOperational(actor.enterpriseId());
        }
        if (!administratorCleanup && item.matchId() != null
                && !store.linkedMatchParticipantsOperational(item.matchId())) {
            throw new PreconditionFailedException(
                    "linked match and both participant enterprises must remain operational");
        }
        if (item.matchId() != null && !store.canAccessLinkedMatch(
                item.matchId(), item.associationId(), item.enterpriseId())) {
            throw new PreconditionFailedException(
                    "linked match participants are outside the collaboration history scope");
        }
    }

    private void requireMatchLink(UUID matchId, UUID associationId, UUID enterpriseId) {
        if (matchId != null && !store.canLinkMatch(matchId, associationId, enterpriseId)) {
            throw new ForbiddenException(
                    "MATCH_LINK_SCOPE_VIOLATION",
                    "the linked match is outside the collaboration data scope");
        }
    }

    private void requireOperational(UUID enterpriseId) {
        if (enterpriseId == null || !enterpriseLifecycle.isOperational(enterpriseId)) {
            throw new PreconditionFailedException(
                    "enterprise must be active before participating in collaboration workflows");
        }
    }

    private static void requireReviewer(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            requireAssociation(actor);
            return;
        }
        if (!actor.isAssociationReviewer()) {
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

    private static void requireActiveFieldsOnly(
            CollaborationView current, CollaborationUpsertRequest request) {
        boolean immutableFieldsMatch = current.title().equals(request.title().trim())
                && current.participants().equals(cleanList(request.participants()))
                && current.priority().equals(priority(request.priority()))
                && Objects.equals(current.matchId(), request.matchId());
        if (!immutableFieldsMatch) {
            throw new PreconditionFailedException(
                    "active collaboration updates may only change owner, next action, due date and progress");
        }
        if (request.progress() != null && request.progress() >= 100) {
            throw new PreconditionFailedException(
                    "active collaboration progress must remain below 100 until completion");
        }
    }

    private static List<String> cleanList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String priority(String value) {
        return value == null || value.isBlank()
                ? "MEDIUM" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizedStage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!FILTER_STAGES.contains(normalized)) {
            throw new ApiException(
                    "INVALID_COLLABORATION_STAGE",
                    "unsupported collaboration stage filter",
                    HttpStatus.BAD_REQUEST);
        }
        return normalized;
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
