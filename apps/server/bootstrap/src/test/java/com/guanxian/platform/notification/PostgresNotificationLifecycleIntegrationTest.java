package com.guanxian.platform.notification;

import com.guanxian.platform.GuanxianApplication;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = GuanxianApplication.class, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.notification.repository=postgres",
        "guanxian.security.mode=demo"
})
class PostgresNotificationLifecycleIntegrationTest {
    private static final UUID ASSOCIATION = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID USER_A = UUID.fromString("91000000-0000-0000-0000-000000000101");
    private static final UUID USER_B = UUID.fromString("91000000-0000-0000-0000-000000000102");
    private static final UUID USER_WITH_ZERO_ARCHIVED =
            UUID.fromString("91000000-0000-0000-0000-000000000103");

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
    NotificationService service;

    @Autowired
    NotificationStore store;

    @BeforeEach
    void prepareUsers() {
        jdbc.update("DELETE FROM notification_message WHERE user_id IN (?, ?, ?)",
                USER_A, USER_B, USER_WITH_ZERO_ARCHIVED);
        jdbc.update("DELETE FROM user_role WHERE user_id IN (?, ?, ?)",
                USER_A, USER_B, USER_WITH_ZERO_ARCHIVED);
        jdbc.update("DELETE FROM user_account WHERE id IN (?, ?, ?)",
                USER_A, USER_B, USER_WITH_ZERO_ARCHIVED);
        jdbc.update("""
                INSERT INTO association (id, name, status)
                VALUES (?, '通知 PostgreSQL 集成测试协会', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, ASSOCIATION);
        insertUser(USER_A, "notification-pg-user-a");
        insertUser(USER_B, "notification-pg-user-b");
        insertUser(USER_WITH_ZERO_ARCHIVED, "notification-pg-user-zero");
        assertInstanceOf(PostgresNotificationStore.class, store);
    }

    @Test
    void archivedFilteringCountingPaginationAndUnreadAreBackedByPostgresAndUserScoped() {
        Instant base = Instant.parse("2026-08-31T10:00:00Z");
        UUID newestArchived = insertMessage(USER_A, "A-newest-archived", "ARCHIVED",
                base.minusSeconds(10), base.minusSeconds(10));
        UUID legacyUnreadArchived = insertMessage(USER_A, "A-legacy-unread-archived", "ARCHIVED",
                null, base.minusSeconds(20));
        UUID unreadDelivered = insertMessage(USER_A, "A-unread-delivered", "DELIVERED",
                null, base.minusSeconds(30));
        insertMessage(USER_A, "A-read", "READ", base.minusSeconds(40), base.minusSeconds(40));
        UUID otherUsersArchived = insertMessage(USER_B, "B-archived", "ARCHIVED",
                null, base.minusSeconds(5));
        insertMessage(USER_WITH_ZERO_ARCHIVED, "zero-user-delivered", "DELIVERED",
                null, base.minusSeconds(1));

        NotificationMessagePage first = service.messages(actor(USER_A), false, "ARCHIVED", 0, 1);
        NotificationMessagePage second = service.messages(actor(USER_A), false, "ARCHIVED", 1, 1);

        assertEquals(2, first.total());
        assertEquals(0, first.page());
        assertEquals(1, first.size());
        assertEquals(newestArchived, first.items().getFirst().id());
        assertEquals(2, second.total());
        assertEquals(legacyUnreadArchived, second.items().getFirst().id());
        assertEquals(1, service.messages(actor(USER_B), false, "ARCHIVED", 0, 20).total());
        assertEquals(otherUsersArchived,
                service.messages(actor(USER_B), false, "ARCHIVED", 0, 20).items().getFirst().id());

        NotificationMessagePage actualZero =
                service.messages(actor(USER_WITH_ZERO_ARCHIVED), false, "ARCHIVED", 0, 20);
        assertEquals(0, actualZero.total());
        assertTrue(actualZero.items().isEmpty());

        NotificationMessagePage unread = service.messages(actor(USER_A), true, null, 0, 20);
        assertEquals(1, unread.total());
        assertEquals(unreadDelivered, unread.items().getFirst().id());
        assertEquals(1, store.countMessages(USER_A, true, null));
    }

    @Test
    void markReadCannotMutateArchivedStatusOrReadTimestamp() {
        Instant originalReadAt = Instant.parse("2026-08-31T09:30:00Z");
        UUID archivedWithTimestamp = insertMessage(USER_A, "archived-read", "ARCHIVED",
                originalReadAt, Instant.parse("2026-08-31T09:00:00Z"));
        UUID archivedWithNullTimestamp = insertMessage(USER_A, "archived-null", "ARCHIVED",
                null, Instant.parse("2026-08-31T08:00:00Z"));

        NotificationMessageView serviceResult = service.markRead(archivedWithTimestamp, actor(USER_A));
        assertEquals("ARCHIVED", serviceResult.status());
        assertEquals(originalReadAt, serviceResult.readAt());

        NotificationMessageView nullTimestampResult = service.markRead(archivedWithNullTimestamp, actor(USER_A));
        assertEquals("ARCHIVED", nullTimestampResult.status());
        assertNull(nullTimestampResult.readAt());
        assertTrue(store.markRead(archivedWithNullTimestamp, USER_A).isEmpty());

        NotificationMessageView persistedWithTimestamp = store.message(archivedWithTimestamp, USER_A).orElseThrow();
        NotificationMessageView persistedWithNullTimestamp = store.message(archivedWithNullTimestamp, USER_A).orElseThrow();
        assertEquals("ARCHIVED", persistedWithTimestamp.status());
        assertEquals(originalReadAt, persistedWithTimestamp.readAt());
        assertEquals("ARCHIVED", persistedWithNullTimestamp.status());
        assertNull(persistedWithNullTimestamp.readAt());
    }

    private void insertUser(UUID id, String username) {
        jdbc.update("""
                INSERT INTO user_account (
                    id, association_id, username, display_name, external_subject, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, ASSOCIATION, username, username, "subject-" + username);
    }

    private UUID insertMessage(
            UUID userId, String title, String status, Instant readAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_message (
                    id, user_id, association_id, notification_type, title, body,
                    resource_type, resource_id, status, idempotency_key,
                    read_at, created_at, delivered_at)
                VALUES (?, ?, ?, 'POLICY', ?, 'PostgreSQL 通知回归测试',
                        'POLICY_DOCUMENT', ?, ?, ?, ?, ?, ?)
                """, id, userId, ASSOCIATION, title, UUID.randomUUID(), status,
                "notification-pg-it:" + id, timestamp(readAt), timestamp(createdAt), timestamp(createdAt));
        return id;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static ActorScope actor(UUID userId) {
        return new ActorScope(userId, "subject-" + userId, "user-" + userId,
                ASSOCIATION, null, Set.of("ENTERPRISE_MEMBER"), Set.of());
    }
}
