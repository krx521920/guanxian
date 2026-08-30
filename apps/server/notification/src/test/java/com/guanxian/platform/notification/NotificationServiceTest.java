package com.guanxian.platform.notification;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationServiceTest {
    private static final UUID ASSOCIATION_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ASSOCIATION_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000203");

    private InMemoryNotificationStore store;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryNotificationStore();
        service = new NotificationService(store);
    }

    @Test
    void subscriptionLifecycleIsOwnedByCurrentUserAndAudited() {
        ActorScope userA = actor(USER_A, ASSOCIATION_A, "ENTERPRISE_MEMBER");
        ActorScope userB = actor(USER_B, ASSOCIATION_A, "ENTERPRISE_MEMBER");

        SubscriptionView created = service.createSubscription(
                new SubscriptionRequest("policy", Map.of("level", "市级"), null), userA);
        assertEquals(List.of("IN_APP"), created.channels());
        assertEquals(1, service.subscriptions(userA).size());
        assertTrue(service.subscriptions(userB).isEmpty());
        assertThrows(NotFoundException.class,
                () -> service.disableSubscription(created.id(), 0, userB));

        SubscriptionView disabled = service.disableSubscription(created.id(), 0, userA);
        assertEquals("INACTIVE", disabled.status());
        assertThrows(PreconditionFailedException.class,
                () -> service.restoreSubscription(created.id(), 0, userA));
        SubscriptionView restored = service.restoreSubscription(created.id(), 1, userA);
        assertEquals("ACTIVE", restored.status());
        service.deleteSubscription(created.id(), 2, userA);
        assertTrue(service.subscriptions(userA).isEmpty());
        assertEquals(4, store.auditCount());
    }

    @Test
    void associationAdminPublishesOnlyToOwnActiveSubscribersAndIsIdempotent() {
        ActorScope userA = actor(USER_A, ASSOCIATION_A, "ENTERPRISE_MEMBER");
        ActorScope userB = actor(USER_B, ASSOCIATION_B, "ENTERPRISE_MEMBER");
        ActorScope adminA = actor(ADMIN, ASSOCIATION_A, "ASSOCIATION_ADMIN");
        service.createSubscription(new SubscriptionRequest("POLICY", Map.of(), List.of("IN_APP")), userA);
        service.createSubscription(new SubscriptionRequest("POLICY", Map.of(), List.of("IN_APP")), userB);

        UUID policyId = UUID.randomUUID();
        store.registerPolicy(policyId, ASSOCIATION_A, "PUBLISHED", false, false);
        PolicyNotificationRequest request = new PolicyNotificationRequest(
                ASSOCIATION_A, policyId, "新政策", "政策正文摘要", "policy-release-1");
        PolicyNotificationResult first = service.publishPolicy(request, adminA);
        PolicyNotificationResult duplicate = service.publishPolicy(request, adminA);

        assertEquals(1, first.recipientCount());
        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(1, store.outboxCount());
        assertEquals(1, service.messages(userA, true, 0, 20).total());
        assertEquals(0, service.messages(userB, true, 0, 20).total());

        NotificationMessageView message = service.messages(userA, true, 0, 20).items().getFirst();
        NotificationMessageView read = service.markRead(message.id(), userA);
        assertNotNull(read.readAt());
        assertEquals("READ", read.status());
        assertEquals(0, service.messages(userA, true, 0, 20).total());
    }

    @Test
    void publisherCannotSelectAnotherAssociation() {
        ActorScope adminA = actor(ADMIN, ASSOCIATION_A, "ASSOCIATION_ADMIN");
        PolicyNotificationRequest request = new PolicyNotificationRequest(
                ASSOCIATION_B, UUID.randomUUID(), "越权", "不应发布", "cross-scope");
        assertThrows(ForbiddenException.class, () -> service.publishPolicy(request, adminA));
        assertEquals(0, store.outboxCount());
    }

    @Test
    void maximumPageNumberDoesNotOverflowTheStoreOffset() {
        NotificationMessagePage page = service.messages(
                actor(USER_A, ASSOCIATION_A, "ENTERPRISE_MEMBER"), false, Integer.MAX_VALUE, 100);

        assertTrue(page.items().isEmpty());
        assertEquals(Integer.MAX_VALUE, page.page());
    }

    @Test
    void unboundJwtIdentityCannotUsePersonalNotificationData() {
        ActorScope unbound = new ActorScope(null, "oidc-sub", "user", ASSOCIATION_A,
                null, Set.of("ENTERPRISE_MEMBER"), Set.of());
        assertThrows(ForbiddenException.class, () -> service.subscriptions(unbound));
        assertThrows(ForbiddenException.class, () -> service.publishPolicy(
                new PolicyNotificationRequest(ASSOCIATION_A, UUID.randomUUID(),
                        "标题", "正文", "unbound"), unbound));
    }

    @Test
    void systemAdministratorReadsFollowSelectedAssociationAndWritesCannotEscapeIt() {
        ActorScope global = systemActor(null);
        ActorScope systemA = systemActor(ASSOCIATION_A);
        ActorScope systemB = systemActor(ASSOCIATION_B);

        SubscriptionView subscriptionA = service.createSubscription(
                new SubscriptionRequest("POLICY", Map.of("scope", "A"), List.of("IN_APP")), systemA);
        SubscriptionView subscriptionB = service.createSubscription(
                new SubscriptionRequest("POLICY", Map.of("scope", "B"), List.of("IN_APP")), systemB);

        assertEquals(2, service.subscriptions(global).size());
        assertEquals(List.of(subscriptionA.id()),
                service.subscriptions(systemA).stream().map(SubscriptionView::id).toList());
        assertEquals(List.of(subscriptionB.id()),
                service.subscriptions(systemB).stream().map(SubscriptionView::id).toList());
        assertThrows(ForbiddenException.class, () -> service.createSubscription(
                new SubscriptionRequest("STANDARD", Map.of(), List.of("IN_APP")), global));
        assertThrows(NotFoundException.class, () -> service.updateSubscription(
                subscriptionA.id(), subscriptionA.version(),
                new SubscriptionRequest("STANDARD", Map.of(), List.of("IN_APP")), systemB));

        UUID policyA = UUID.randomUUID();
        UUID policyB = UUID.randomUUID();
        store.registerPolicy(policyA, ASSOCIATION_A, "PUBLISHED", false, false);
        store.registerPolicy(policyB, ASSOCIATION_B, "PUBLISHED", false, false);
        service.publishPolicy(new PolicyNotificationRequest(
                ASSOCIATION_A, policyA, "A 政策", "A 正文", "system-a"), systemA);
        service.publishPolicy(new PolicyNotificationRequest(
                ASSOCIATION_B, policyB, "B 政策", "B 正文", "system-b"), systemB);

        assertEquals(2, service.messages(global, false, 0, 20).total());
        assertEquals(1, service.messages(systemA, false, 0, 20).total());
        NotificationMessageView messageB = service.messages(systemB, false, 0, 20).items().getFirst();
        assertThrows(NotFoundException.class, () -> service.markRead(messageB.id(), systemA));
        assertEquals("READ", service.markRead(messageB.id(), systemB).status());
    }

    @Test
    void policyNotificationRequiresPublishedActivePolicyInTheSelectedAssociation() {
        ActorScope adminA = actor(ADMIN, ASSOCIATION_A, "ASSOCIATION_ADMIN");
        UUID foreign = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        store.registerPolicy(foreign, ASSOCIATION_B, "PUBLISHED", false, false);
        store.registerPolicy(draft, ASSOCIATION_A, "DRAFT", false, false);
        store.registerPolicy(disabled, ASSOCIATION_A, "PUBLISHED", true, false);
        store.registerPolicy(deleted, ASSOCIATION_A, "PUBLISHED", false, true);

        for (UUID policyId : List.of(foreign, draft, disabled, deleted, UUID.randomUUID())) {
            assertThrows(NotFoundException.class, () -> service.publishPolicy(
                    new PolicyNotificationRequest(
                            ASSOCIATION_A, policyId, "不可发布", "无消息应生成", "invalid-" + policyId),
                    adminA));
        }
        assertEquals(0, store.outboxCount());
        assertEquals(0, store.auditCount());
    }

    private static ActorScope actor(UUID userId, UUID associationId, String role) {
        return new ActorScope(userId, "subject-" + userId, "user-" + userId,
                associationId, null, Set.of(role), Set.of());
    }

    private static ActorScope systemActor(UUID associationId) {
        return new ActorScope(ADMIN, "system-subject", "system", associationId,
                null, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
