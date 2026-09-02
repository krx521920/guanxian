package com.guanxian.platform.notification;

import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationService {
    private static final Set<String> TYPES = Set.of("POLICY");
    private static final Set<String> MESSAGE_STATUSES = Set.of(
            "PENDING", "DELIVERED", "READ", "FAILED", "ARCHIVED");

    private final NotificationStore store;

    public NotificationService(NotificationStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> subscriptions(ActorScope actor) {
        return store.subscriptions(requireUser(actor), readAssociation(actor));
    }

    @Transactional
    public SubscriptionView createSubscription(SubscriptionRequest request, ActorScope actor) {
        UUID userId = requireUser(actor);
        UUID associationId = requireWriteAssociation(actor);
        SubscriptionRequest normalized = normalize(request);
        SubscriptionView created;
        try {
            created = store.createSubscription(userId, associationId, normalized);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ConflictException("a subscription of this type already exists for the current user");
        }
        auditSubscription(actor, "CREATE", created);
        return created;
    }

    @Transactional
    public SubscriptionView updateSubscription(
            UUID id, long expectedVersion, SubscriptionRequest request, ActorScope actor) {
        UUID userId = requireUser(actor);
        UUID associationId = requireWriteAssociation(actor);
        SubscriptionView current = getSubscription(id, userId, associationId);
        requireVersion(current.version(), expectedVersion);
        SubscriptionView updated;
        try {
            updated = store.updateSubscription(id, userId, associationId, expectedVersion, normalize(request))
                    .orElseThrow(NotificationService::stale);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ConflictException("a subscription of this type already exists for the current user");
        }
        auditSubscription(actor, "UPDATE", updated);
        return updated;
    }

    @Transactional
    public SubscriptionView disableSubscription(UUID id, long expectedVersion, ActorScope actor) {
        return changeStatus(id, expectedVersion, "INACTIVE", "DISABLE", actor);
    }

    @Transactional
    public SubscriptionView restoreSubscription(UUID id, long expectedVersion, ActorScope actor) {
        return changeStatus(id, expectedVersion, "ACTIVE", "RESTORE", actor);
    }

    @Transactional
    public void deleteSubscription(UUID id, long expectedVersion, ActorScope actor) {
        UUID userId = requireUser(actor);
        UUID associationId = requireWriteAssociation(actor);
        SubscriptionView current = getSubscription(id, userId, associationId);
        requireVersion(current.version(), expectedVersion);
        if (!store.deleteSubscription(id, userId, associationId, expectedVersion)) {
            throw stale();
        }
        auditSubscription(actor, "DELETE", current);
    }

    @Transactional(readOnly = true)
    public NotificationMessagePage messages(ActorScope actor, boolean unreadOnly, int page, int size) {
        return messages(actor, unreadOnly, null, page, size);
    }

    @Transactional(readOnly = true)
    public NotificationMessagePage messages(
            ActorScope actor, boolean unreadOnly, String requestedStatus, int page, int size) {
        UUID userId = requireUser(actor);
        UUID associationId = readAssociation(actor);
        String status = normalizeMessageStatus(requestedStatus);
        if (unreadOnly && status != null) {
            throw new PreconditionFailedException("unreadOnly cannot be combined with a message status filter");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) safePage * safeSize;
        return new NotificationMessagePage(
                store.messages(userId, associationId, unreadOnly, status, offset, safeSize),
                store.countMessages(userId, associationId, unreadOnly, status), safePage, safeSize);
    }

    @Transactional
    public NotificationMessageView markRead(UUID id, ActorScope actor) {
        UUID userId = requireUser(actor);
        UUID associationId = readAssociation(actor);
        Optional<NotificationMessageView> changed = store.markRead(id, userId, associationId);
        if (changed.isPresent()) {
            NotificationMessageView updated = changed.get();
            store.audit(actor, updated.associationId(), "MARK_READ", "NOTIFICATION_MESSAGE", id,
                    Map.of("notificationType", updated.notificationType()));
            return updated;
        }
        NotificationMessageView current = store.message(id, userId, associationId)
                .orElseThrow(() -> new NotFoundException("notification_message", id));
        if ("ARCHIVED".equals(current.status())) {
            throw new PreconditionFailedException("an archived notification must be restored before it can be read");
        }
        if (current.readAt() != null) {
            return current;
        }
        throw new PreconditionFailedException("only a delivered notification can be marked as read");
    }

    @Transactional
    public NotificationMessageView archive(UUID id, ActorScope actor) {
        return changeMessageState(id, "ARCHIVE", true, actor);
    }

    @Transactional
    public NotificationMessageView restore(UUID id, ActorScope actor) {
        return changeMessageState(id, "RESTORE", false, actor);
    }

    @Transactional
    public PolicyNotificationResult publishPolicy(PolicyNotificationRequest request, ActorScope actor) {
        requireUser(actor);
        UUID associationId = publishAssociation(request.associationId(), actor);
        if (!store.policyBelongsToAssociation(request.policyId(), associationId)) {
            throw new NotFoundException("published_policy", request.policyId());
        }
        PolicyNotificationRequest normalized = new PolicyNotificationRequest(
                associationId, request.policyId(), request.title().trim(), request.body().trim(),
                request.idempotencyKey().trim());
        PolicyNotificationResult result = store.publishPolicy(associationId, normalized, actor);
        if (!result.duplicate()) {
            store.audit(actor, associationId, "PUBLISH", "POLICY_NOTIFICATION", request.policyId(),
                    Map.of("recipientCount", result.recipientCount(),
                            "idempotencyKey", normalized.idempotencyKey()));
        }
        return result;
    }

    private SubscriptionView changeStatus(
            UUID id, long expectedVersion, String status, String action, ActorScope actor) {
        UUID userId = requireUser(actor);
        UUID associationId = requireWriteAssociation(actor);
        SubscriptionView current = getSubscription(id, userId, associationId);
        requireVersion(current.version(), expectedVersion);
        if (status.equals(current.status())) {
            throw new PreconditionFailedException("subscription is already " + status.toLowerCase(Locale.ROOT));
        }
        if ("ACTIVE".equals(status)) {
            normalize(new SubscriptionRequest(
                    current.subscriptionType(), current.filters(), current.channels()));
        }
        SubscriptionView updated = store.changeSubscriptionStatus(
                        id, userId, associationId, expectedVersion, status)
                .orElseThrow(NotificationService::stale);
        auditSubscription(actor, action, updated);
        return updated;
    }

    private SubscriptionView getSubscription(UUID id, UUID userId, UUID associationId) {
        return store.subscription(id, userId, associationId)
                .orElseThrow(() -> new NotFoundException("notification_subscription", id));
    }

    private void auditSubscription(ActorScope actor, String action, SubscriptionView value) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subscriptionType", value.subscriptionType());
        details.put("status", value.status());
        details.put("version", value.version());
        store.audit(actor, value.associationId(), action,
                "NOTIFICATION_SUBSCRIPTION", value.id(), value.version(), Map.copyOf(details));
    }

    private static SubscriptionRequest normalize(SubscriptionRequest request) {
        String type = request.subscriptionType().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new PreconditionFailedException("unsupported notification subscription type");
        }
        List<String> channels = request.channels() == null || request.channels().isEmpty()
                ? List.of("IN_APP")
                : request.channels().stream().map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!channels.equals(List.of("IN_APP"))) {
            throw new PreconditionFailedException("only the IN_APP notification channel is currently supported");
        }
        Map<String, Object> filters = request.filters() == null ? Map.of() : Map.copyOf(request.filters());
        if (!filters.isEmpty()) {
            throw new PreconditionFailedException(
                    "filtered subscriptions are not implemented; use an empty filter for all policy notifications");
        }
        return new SubscriptionRequest(type, filters, channels);
    }

    private static UUID requireUser(ActorScope actor) {
        if (actor.userId() == null) {
            throw new ForbiddenException("IDENTITY_NOT_BOUND",
                    "authenticated identity is not bound to an active user account");
        }
        return actor.userId();
    }

    private static UUID publishAssociation(UUID requested, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            UUID selected = requested == null ? actor.associationId() : requested;
            if (selected == null) {
                throw new ForbiddenException("NOTIFICATION_PUBLISH_SCOPE_REQUIRED",
                        "system administrators must select or provide an association context");
            }
            if (actor.associationId() != null && requested != null
                    && !requested.equals(actor.associationId())) {
                throw new ForbiddenException("NOTIFICATION_SCOPE_VIOLATION",
                        "the requested association does not match the selected context");
            }
            return selected;
        }
        if (!actor.isAssociationReviewer() || actor.associationId() == null) {
            throw new ForbiddenException("NOTIFICATION_PUBLISH_SCOPE_REQUIRED",
                    "an association administrator identity is required");
        }
        if (requested != null && !requested.equals(actor.associationId())) {
            throw new ForbiddenException("NOTIFICATION_SCOPE_VIOLATION",
                    "association administrators can only publish within their own association");
        }
        return actor.associationId();
    }

    private NotificationMessageView changeMessageState(
            UUID id, String action, boolean archive, ActorScope actor) {
        UUID userId = requireUser(actor);
        UUID associationId = readAssociation(actor);
        Optional<NotificationMessageView> changed = archive
                ? store.archive(id, userId, associationId)
                : store.restore(id, userId, associationId);
        if (changed.isPresent()) {
            NotificationMessageView updated = changed.get();
            store.audit(actor, updated.associationId(), action, "NOTIFICATION_MESSAGE", id,
                    Map.of("notificationType", updated.notificationType(), "status", updated.status()));
            return updated;
        }
        NotificationMessageView current = store.message(id, userId, associationId)
                .orElseThrow(() -> new NotFoundException("notification_message", id));
        if (archive && !"ARCHIVED".equals(current.status())
                && !Set.of("DELIVERED", "READ").contains(current.status())) {
            throw new PreconditionFailedException("only a delivered or read notification can be archived");
        }
        return current;
    }

    private static String normalizeMessageStatus(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String status = requested.trim().toUpperCase(Locale.ROOT);
        if (!MESSAGE_STATUSES.contains(status)) {
            throw new PreconditionFailedException("unsupported notification message status");
        }
        return status;
    }

    private static UUID readAssociation(ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return actor.associationId();
        }
        return requireWriteAssociation(actor);
    }

    private static UUID requireWriteAssociation(ActorScope actor) {
        if (actor.associationId() == null) {
            throw new ForbiddenException("NOTIFICATION_ASSOCIATION_CONTEXT_REQUIRED",
                    "an association context is required for notification writes");
        }
        return actor.associationId();
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static PreconditionFailedException stale() {
        return new PreconditionFailedException("resource version is stale; reload and retry with the latest ETag");
    }
}
