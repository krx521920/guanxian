package com.guanxian.platform.notification;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.notification.repository", havingValue = "memory")
class InMemoryNotificationStore implements NotificationStore {
    private final Map<UUID, SubscriptionView> subscriptions = new LinkedHashMap<>();
    private final Map<UUID, NotificationMessageView> messages = new LinkedHashMap<>();
    private final Map<UUID, PolicyState> policies = new LinkedHashMap<>();
    private final Set<String> outboxKeys = new HashSet<>();
    private final List<Map<String, Object>> audits = new ArrayList<>();

    @Override
    public synchronized List<SubscriptionView> subscriptions(UUID userId, UUID associationId) {
        return subscriptions.values().stream().filter(value -> userId.equals(value.userId()))
                .filter(value -> associationId == null || associationId.equals(value.associationId()))
                .sorted(Comparator.comparing(SubscriptionView::updatedAt).reversed()).toList();
    }

    @Override
    public synchronized Optional<SubscriptionView> subscription(UUID id, UUID userId, UUID associationId) {
        return Optional.ofNullable(subscriptions.get(id)).filter(value -> userId.equals(value.userId()))
                .filter(value -> associationId == null || associationId.equals(value.associationId()));
    }

    @Override
    public synchronized SubscriptionView createSubscription(
            UUID userId, UUID associationId, SubscriptionRequest request) {
        if (subscriptions.values().stream().anyMatch(value -> userId.equals(value.userId())
                && java.util.Objects.equals(associationId, value.associationId())
                && request.subscriptionType().equals(value.subscriptionType()))) {
            throw new org.springframework.dao.DuplicateKeyException("duplicate subscription type");
        }
        Instant now = Instant.now();
        SubscriptionView created = new SubscriptionView(UUID.randomUUID(), userId, associationId,
                request.subscriptionType(), request.filters(), request.channels(), "ACTIVE", 0, now, now);
        subscriptions.put(created.id(), created);
        return created;
    }

    @Override
    public synchronized Optional<SubscriptionView> updateSubscription(
            UUID id, UUID userId, UUID associationId, long expectedVersion, SubscriptionRequest request) {
        Optional<SubscriptionView> found = subscription(id, userId, associationId)
                .filter(value -> value.version() == expectedVersion);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (subscriptions.values().stream().anyMatch(value -> !id.equals(value.id())
                && userId.equals(value.userId())
                && java.util.Objects.equals(associationId, value.associationId())
                && request.subscriptionType().equals(value.subscriptionType()))) {
            throw new org.springframework.dao.DuplicateKeyException("duplicate subscription type");
        }
        SubscriptionView current = found.get();
        SubscriptionView updated = new SubscriptionView(id, userId, current.associationId(),
                request.subscriptionType(), request.filters(), request.channels(), current.status(),
                current.version() + 1, current.createdAt(), Instant.now());
        subscriptions.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<SubscriptionView> changeSubscriptionStatus(
            UUID id, UUID userId, UUID associationId, long expectedVersion, String status) {
        Optional<SubscriptionView> found = subscription(id, userId, associationId)
                .filter(value -> value.version() == expectedVersion);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SubscriptionView current = found.get();
        SubscriptionView updated = new SubscriptionView(id, userId, current.associationId(),
                current.subscriptionType(), current.filters(), current.channels(), status,
                current.version() + 1, current.createdAt(), Instant.now());
        subscriptions.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized boolean deleteSubscription(
            UUID id, UUID userId, UUID associationId, long expectedVersion) {
        return subscription(id, userId, associationId).filter(value -> value.version() == expectedVersion)
                .map(value -> subscriptions.remove(id, value)).orElse(false);
    }

    @Override
    public synchronized List<NotificationMessageView> messages(
            UUID userId, UUID associationId, boolean unreadOnly, String status, long offset, int limit) {
        return messages.values().stream().filter(value -> userId.equals(value.userId()))
                .filter(value -> associationId == null || associationId.equals(value.associationId()))
                .filter(value -> messageMatches(value, unreadOnly, status))
                .sorted(Comparator.comparing(NotificationMessageView::createdAt).reversed()
                        .thenComparing(NotificationMessageView::id))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long countMessages(
            UUID userId, UUID associationId, boolean unreadOnly, String status) {
        return messages.values().stream().filter(value -> userId.equals(value.userId()))
                .filter(value -> associationId == null || associationId.equals(value.associationId()))
                .filter(value -> messageMatches(value, unreadOnly, status)).count();
    }

    @Override
    public synchronized Optional<NotificationMessageView> message(UUID id, UUID userId, UUID associationId) {
        return Optional.ofNullable(messages.get(id)).filter(value -> userId.equals(value.userId()))
                .filter(value -> associationId == null || associationId.equals(value.associationId()));
    }

    @Override
    public synchronized Optional<NotificationMessageView> markRead(
            UUID id, UUID userId, UUID associationId) {
        Optional<NotificationMessageView> found = message(id, userId, associationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        NotificationMessageView current = found.get();
        if (current.readAt() != null || !"DELIVERED".equals(current.status())) {
            return Optional.empty();
        }
        NotificationMessageView updated = new NotificationMessageView(
                current.id(), current.userId(), current.associationId(), current.notificationType(),
                current.title(), current.body(), current.resourceType(), current.resourceId(),
                "READ", Instant.now(), current.createdAt(), current.deliveredAt());
        messages.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<NotificationMessageView> archive(
            UUID id, UUID userId, UUID associationId) {
        Optional<NotificationMessageView> found = message(id, userId, associationId);
        if (found.isEmpty() || !Set.of("DELIVERED", "READ").contains(found.get().status())) {
            return Optional.empty();
        }
        NotificationMessageView current = found.get();
        NotificationMessageView updated = copyWithStatus(current, "ARCHIVED", current.readAt());
        messages.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<NotificationMessageView> restore(
            UUID id, UUID userId, UUID associationId) {
        Optional<NotificationMessageView> found = message(id, userId, associationId);
        if (found.isEmpty() || !"ARCHIVED".equals(found.get().status())) {
            return Optional.empty();
        }
        NotificationMessageView current = found.get();
        NotificationMessageView updated = copyWithStatus(
                current, current.readAt() == null ? "DELIVERED" : "READ", current.readAt());
        messages.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized boolean policyBelongsToAssociation(UUID policyId, UUID associationId) {
        PolicyState policy = policies.get(policyId);
        return policy != null
                && policy.associationId().equals(associationId)
                && "PUBLISHED".equals(policy.status())
                && !policy.disabled()
                && !policy.deleted();
    }

    @Override
    public synchronized PolicyNotificationResult publishPolicy(
            UUID associationId, PolicyNotificationRequest request, ActorScope actor) {
        String eventKey = associationId + ":" + request.policyId() + ":" + request.idempotencyKey();
        if (!outboxKeys.add(eventKey)) {
            long count = messages.values().stream()
                    .filter(value -> request.policyId().equals(value.resourceId())
                            && associationId.equals(value.associationId())).count();
            return new PolicyNotificationResult(request.policyId(), associationId, (int) count, true);
        }
        Instant now = Instant.now();
        int count = 0;
        for (SubscriptionView subscription : subscriptions.values()) {
            if (associationId.equals(subscription.associationId())
                    && "POLICY".equals(subscription.subscriptionType())
                    && "ACTIVE".equals(subscription.status())
                    && subscription.channels().contains("IN_APP")
                    && subscription.filters().isEmpty()) {
                UUID id = UUID.randomUUID();
                messages.put(id, new NotificationMessageView(id, subscription.userId(), associationId,
                        "POLICY", request.title(), request.body(), "POLICY_DOCUMENT", request.policyId(),
                        "DELIVERED", null, now, now));
                count++;
            }
        }
        return new PolicyNotificationResult(request.policyId(), associationId, count, false);
    }

    @Override
    public synchronized void audit(
            ActorScope actor, UUID associationId, String action, String resourceType,
            UUID resourceId, Long resourceVersion, Map<String, Object> details) {
        Map<String, Object> audit = new HashMap<>(details);
        audit.put("action", action);
        audit.put("resourceType", resourceType);
        audit.put("resourceId", resourceId);
        audit.put("actorSubject", actor.subject());
        if (resourceVersion != null) {
            audit.put("resourceVersion", resourceVersion);
        }
        audits.add(Map.copyOf(audit));
    }

    synchronized int auditCount() {
        return audits.size();
    }

    synchronized Map<String, Object> latestAudit() {
        return audits.isEmpty() ? Map.of() : audits.getLast();
    }

    synchronized int outboxCount() {
        return outboxKeys.size();
    }

    synchronized void registerPolicy(
            UUID policyId,
            UUID associationId,
            String status,
            boolean disabled,
            boolean deleted) {
        policies.put(
                Objects.requireNonNull(policyId, "policyId"),
                new PolicyState(
                        Objects.requireNonNull(associationId, "associationId"),
                        Objects.requireNonNull(status, "status"),
                        disabled,
                        deleted));
    }

    private record PolicyState(UUID associationId, String status, boolean disabled, boolean deleted) {
    }

    private static boolean messageMatches(
            NotificationMessageView value, boolean unreadOnly, String status) {
        if (unreadOnly) {
            return value.readAt() == null && "DELIVERED".equals(value.status());
        }
        return status == null
                ? Set.of("DELIVERED", "READ").contains(value.status())
                : status.equals(value.status());
    }

    private static NotificationMessageView copyWithStatus(
            NotificationMessageView current, String status, Instant readAt) {
        return new NotificationMessageView(
                current.id(), current.userId(), current.associationId(), current.notificationType(),
                current.title(), current.body(), current.resourceType(), current.resourceId(),
                status, readAt, current.createdAt(), current.deliveredAt());
    }
}
