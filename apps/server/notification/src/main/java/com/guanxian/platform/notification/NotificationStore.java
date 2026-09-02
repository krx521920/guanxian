package com.guanxian.platform.notification;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

interface NotificationStore {
    List<SubscriptionView> subscriptions(UUID userId, UUID associationId);
    Optional<SubscriptionView> subscription(UUID id, UUID userId, UUID associationId);
    SubscriptionView createSubscription(UUID userId, UUID associationId, SubscriptionRequest request);
    Optional<SubscriptionView> updateSubscription(
            UUID id, UUID userId, UUID associationId, long expectedVersion, SubscriptionRequest request);
    Optional<SubscriptionView> changeSubscriptionStatus(
            UUID id, UUID userId, UUID associationId, long expectedVersion, String status);
    boolean deleteSubscription(UUID id, UUID userId, UUID associationId, long expectedVersion);
    List<NotificationMessageView> messages(
            UUID userId, UUID associationId, boolean unreadOnly, String status, long offset, int limit);
    long countMessages(UUID userId, UUID associationId, boolean unreadOnly, String status);
    Optional<NotificationMessageView> message(UUID id, UUID userId, UUID associationId);
    Optional<NotificationMessageView> markRead(UUID id, UUID userId, UUID associationId);
    Optional<NotificationMessageView> archive(UUID id, UUID userId, UUID associationId);
    Optional<NotificationMessageView> restore(UUID id, UUID userId, UUID associationId);
    boolean policyBelongsToAssociation(UUID policyId, UUID associationId);
    PolicyNotificationResult publishPolicy(
            UUID associationId, PolicyNotificationRequest request, ActorScope actor);
    default void audit(ActorScope actor, UUID associationId, String action,
                       String resourceType, UUID resourceId, Map<String, Object> details) {
        audit(actor, associationId, action, resourceType, resourceId, null, details);
    }
    void audit(ActorScope actor, UUID associationId, String action,
               String resourceType, UUID resourceId, Long resourceVersion,
               Map<String, Object> details);
}
