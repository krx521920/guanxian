package com.guanxian.platform.notification;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void archivedMessagesUseServerSideStatusFilteringCountingAndPagination() {
        ActorScope userA = actor(USER_A, ASSOCIATION_A, "ENTERPRISE_MEMBER");
        Instant now = Instant.parse("2026-08-31T10:00:00Z");
        NotificationMessageView newestArchived = message(
                USER_A, "ARCHIVED", now, now.minusSeconds(60));
        NotificationMessageView olderArchivedWithLegacyUnreadTimestamp = message(
                USER_A, "ARCHIVED", null, now.minusSeconds(120));
        NotificationMessageView delivered = message(
                USER_A, "DELIVERED", null, now.minusSeconds(180));
        store.addMessage(newestArchived);
        store.addMessage(olderArchivedWithLegacyUnreadTimestamp);
        store.addMessage(delivered);
        store.addMessage(message(USER_B, "ARCHIVED", now, now.minusSeconds(30)));

        NotificationMessagePage first = service.messages(userA, false, " archived ", 0, 1);
        NotificationMessagePage second = service.messages(userA, false, "ARCHIVED", 1, 1);

        assertEquals(2, first.total());
        assertEquals(0, first.page());
        assertEquals(1, first.size());
        assertEquals(List.of(newestArchived), first.items());
        assertEquals(List.of(olderArchivedWithLegacyUnreadTimestamp), second.items());
        assertEquals(1, service.messages(userA, true, null, 0, 20).total());
        assertEquals(3, service.messages(userA, false, null, 0, 20).total());
    }

    @Test
    void messageFiltersRejectUnsupportedOrAmbiguousRequests() {
        ActorScope userA = actor(USER_A, ASSOCIATION_A, "ENTERPRISE_MEMBER");

        assertThrows(ApiException.class,
                () -> service.messages(userA, false, "NOT_A_STATUS", 0, 20));
        assertThrows(ApiException.class,
                () -> service.messages(userA, true, "ARCHIVED", 0, 20));
    }

    @Test
    void archivedMessageCannotBeChangedBackToRead() {
        ActorScope userA = actor(USER_A, ASSOCIATION_A, "ENTERPRISE_MEMBER");
        NotificationMessageView archived = message(USER_A, "ARCHIVED", null,
                Instant.parse("2026-08-31T10:00:00Z"));
        store.addMessage(archived);

        NotificationMessageView result = service.markRead(archived.id(), userA);

        assertSame(archived, result);
        assertEquals("ARCHIVED", result.status());
        assertNull(result.readAt());
        assertEquals(0, store.auditCount());
        assertTrue(store.markRead(archived.id(), USER_A).isEmpty());
        assertEquals("ARCHIVED", store.message(archived.id(), USER_A).orElseThrow().status());
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

    private static ActorScope actor(UUID userId, UUID associationId, String role) {
        return new ActorScope(userId, "subject-" + userId, "user-" + userId,
                associationId, null, Set.of(role), Set.of());
    }

    private static NotificationMessageView message(
            UUID userId, String status, Instant readAt, Instant createdAt) {
        return new NotificationMessageView(UUID.randomUUID(), userId, ASSOCIATION_A,
                "POLICY", "测试通知", "测试正文", "POLICY_DOCUMENT", UUID.randomUUID(),
                status, readAt, createdAt, createdAt);
    }
}
