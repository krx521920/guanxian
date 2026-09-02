package com.guanxian.platform.iam;

import com.guanxian.platform.notification.NotificationService;
import com.guanxian.platform.notification.PolicyNotificationRequest;
import com.guanxian.platform.notification.SubscriptionRequest;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.notification.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=demo"
})
@Transactional
class PostgresSystemContextScopeIntegrationTest {
    private static final UUID ASSOCIATION_A = UUID.fromString("74000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B = UUID.fromString("74000000-0000-0000-0000-000000000002");
    private static final UUID ASSOCIATION_C = UUID.fromString("74000000-0000-0000-0000-000000000003");
    private static final UUID ENTERPRISE_A = UUID.fromString("74000000-0000-0000-0000-000000000101");
    private static final UUID ENTERPRISE_A2 = UUID.fromString("74000000-0000-0000-0000-000000000102");
    private static final UUID SYSTEM_USER = UUID.fromString("74000000-0000-0000-0000-000000000201");
    private static final UUID TENANT_USER = UUID.fromString("74000000-0000-0000-0000-000000000202");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CrossAssociationService crossAssociations;

    @Autowired
    NotificationService notifications;

    @Test
    void postgresAdaptersKeepSystemReadsAndWritesInsideSelectedContext() {
        seedScopes();
        ActorScope global = system(null, null);
        ActorScope associationA = system(ASSOCIATION_A, null);
        ActorScope associationB = system(ASSOCIATION_B, null);
        ActorScope associationC = system(ASSOCIATION_C, null);

        var requestA = crossAssociations.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, ASSOCIATION_B, "A to B"), associationA);
        crossAssociations.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, ASSOCIATION_B, "C to B"), associationC);

        assertEquals(2, crossAssociations.accessRequests(global).size());
        assertEquals(1, crossAssociations.accessRequests(associationA).size());
        assertEquals(2, crossAssociations.accessRequests(associationB).size());
        assertThrows(ForbiddenException.class, () -> crossAssociations.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(
                        ASSOCIATION_C, ASSOCIATION_B, "body must not replace header"), associationA));
        assertThrows(ForbiddenException.class, () -> crossAssociations.reviewAccessRequest(
                requestA.id(), requestA.version(), rejected(), global));
        assertThrows(ForbiddenException.class, () -> crossAssociations.reviewAccessRequest(
                requestA.id(), requestA.version(), rejected(), associationA));
        assertEquals("REJECTED", crossAssociations.reviewAccessRequest(
                requestA.id(), requestA.version(), rejected(), associationB).status());
        assertThrows(ForbiddenException.class, () -> crossAssociations.grantConsent(
                new CrossAssociationDtos.ConsentCreate(
                        ENTERPRISE_A2, ASSOCIATION_B, "PRODUCT", UUID.randomUUID(), null),
                system(ASSOCIATION_A, ENTERPRISE_A)));

        var subscriptionA = notifications.createSubscription(
                new SubscriptionRequest("POLICY", Map.of(), List.of("IN_APP")), associationA);
        var subscriptionB = notifications.createSubscription(
                new SubscriptionRequest("POLICY", Map.of(), List.of("IN_APP")), associationB);

        assertEquals(2, notifications.subscriptions(global).size());
        assertEquals(List.of(subscriptionA.id()),
                notifications.subscriptions(associationA).stream().map(value -> value.id()).toList());
        assertEquals(List.of(subscriptionB.id()),
                notifications.subscriptions(associationB).stream().map(value -> value.id()).toList());
        assertThrows(ForbiddenException.class, () -> notifications.createSubscription(
                new SubscriptionRequest("STANDARD", Map.of(), List.of("IN_APP")), global));
        assertThrows(NotFoundException.class, () -> notifications.updateSubscription(
                subscriptionA.id(), subscriptionA.version(),
                new SubscriptionRequest("STANDARD", Map.of(), List.of("IN_APP")), associationB));

        UUID messageA = insertMessage(ASSOCIATION_A, "A message");
        UUID messageB = insertMessage(ASSOCIATION_B, "B message");
        assertEquals(2, notifications.messages(global, false, 0, 20).total());
        assertEquals(1, notifications.messages(associationA, false, 0, 20).total());
        assertThrows(NotFoundException.class, () -> notifications.markRead(messageB, associationA));
        assertEquals("READ", notifications.markRead(messageB, associationB).status());
        assertEquals("READ", notifications.markRead(messageB, associationB).status());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE action = 'MARK_READ' AND resource_type = 'NOTIFICATION_MESSAGE'
                   AND resource_id = ?
                """, Integer.class, messageB.toString()));
        assertEquals("ARCHIVED", notifications.archive(messageB, associationB).status());
        assertEquals("ARCHIVED", notifications.archive(messageB, associationB).status());
        assertEquals(0, notifications.messages(associationB, false, 0, 20).total());
        assertEquals(1, notifications.messages(
                associationB, false, "ARCHIVED", 0, 20).total());
        assertEquals("READ", notifications.restore(messageB, associationB).status());
        assertEquals("READ", notifications.restore(messageB, associationB).status());
        assertTrue(notifications.messages(associationA, false, 0, 20).items().stream()
                .anyMatch(value -> value.id().equals(messageA)));
    }

    @Test
    void postgresDeliveryRechecksTheUsersCurrentAssociationBinding() {
        seedScopes();
        jdbc.update("""
                INSERT INTO user_account (
                    id, association_id, username, display_name, external_subject, status)
                VALUES (?, ?, 'tenant-recipient', '租户收件人', 'tenant-recipient-subject', 'ACTIVE')
                """, TENANT_USER, ASSOCIATION_A);
        jdbc.update("""
                INSERT INTO notification_subscription (
                    user_id, association_id, subscription_type, filters, channels, status)
                VALUES (?, ?, 'POLICY', '{}'::jsonb, '["IN_APP"]'::jsonb, 'ACTIVE')
                """, TENANT_USER, ASSOCIATION_A);
        UUID policyId = jdbc.queryForObject("""
                INSERT INTO policy_document (title, association_id, status)
                VALUES ('跨租户投递校验', ?, 'PUBLISHED') RETURNING id
                """, UUID.class, ASSOCIATION_A);

        jdbc.update("UPDATE user_account SET association_id = ? WHERE id = ?", ASSOCIATION_B, TENANT_USER);
        var result = notifications.publishPolicy(new PolicyNotificationRequest(
                ASSOCIATION_A, policyId, "不应投递", "用户已迁出协会", "stale-binding"),
                system(ASSOCIATION_A, null));

        assertEquals(0, result.recipientCount());
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM notification_message
                 WHERE user_id = ? AND association_id = ? AND resource_id = ?
                """, Integer.class, TENANT_USER, ASSOCIATION_A, policyId));
    }

    private void seedScopes() {
        jdbc.update("""
                INSERT INTO association (id, name, status) VALUES
                    (?, '上下文协会 A', 'ACTIVE'),
                    (?, '上下文协会 B', 'ACTIVE'),
                    (?, '上下文协会 C', 'ACTIVE')
                """, ASSOCIATION_A, ASSOCIATION_B, ASSOCIATION_C);
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status) VALUES
                    (?, ?, '上下文企业 A1', '测试单位', 'ACTIVE'),
                    (?, ?, '上下文企业 A2', '测试单位', 'ACTIVE')
                """, ENTERPRISE_A, ASSOCIATION_A, ENTERPRISE_A2, ASSOCIATION_A);
        jdbc.update("""
                INSERT INTO user_account (id, username, display_name, external_subject, status)
                VALUES (?, 'context-system', '上下文系统管理员', 'context-system-subject', 'ACTIVE')
                """, SYSTEM_USER);
        jdbc.update("INSERT INTO user_role (user_id, role_code) VALUES (?, 'SYSTEM_ADMIN')", SYSTEM_USER);
    }

    private UUID insertMessage(UUID associationId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO notification_message (
                    user_id, association_id, notification_type, title, body,
                    status, idempotency_key, delivered_at)
                VALUES (?, ?, 'POLICY', ?, 'body', 'DELIVERED', ?, now())
                RETURNING id
                """, UUID.class, SYSTEM_USER, associationId, title, UUID.randomUUID().toString());
    }

    private static CrossAssociationDtos.AccessRequestReview rejected() {
        return new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.REJECT, null, null, null);
    }

    private static ActorScope system(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                SYSTEM_USER, "context-system-subject", "context-system",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
