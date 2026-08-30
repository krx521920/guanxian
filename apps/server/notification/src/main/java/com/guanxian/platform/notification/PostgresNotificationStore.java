package com.guanxian.platform.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.notification.repository", havingValue = "postgres", matchIfMissing = true)
class PostgresNotificationStore implements NotificationStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final String SUBSCRIPTION_SELECT = """
            SELECT id, user_id, association_id, subscription_type, filters::text AS filters,
                   channels::text AS channels, status, version, created_at, updated_at
              FROM notification_subscription
            """;
    private static final String MESSAGE_SELECT = """
            SELECT id, user_id, association_id, notification_type, title, body, resource_type,
                   resource_id, status, read_at, created_at, delivered_at
              FROM notification_message
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<SubscriptionView> subscriptionMapper = this::mapSubscription;
    private final RowMapper<NotificationMessageView> messageMapper = this::mapMessage;

    PostgresNotificationStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SubscriptionView> subscriptions(UUID userId, UUID associationId) {
        return jdbc.query(SUBSCRIPTION_SELECT
                        + " WHERE user_id = :userId" + readAssociationClause(associationId)
                        + " ORDER BY updated_at DESC, id",
                ids(null, userId, associationId), subscriptionMapper);
    }

    @Override
    public Optional<SubscriptionView> subscription(UUID id, UUID userId, UUID associationId) {
        return jdbc.query(SUBSCRIPTION_SELECT + " WHERE id = :id AND user_id = :userId"
                        + readAssociationClause(associationId),
                ids(id, userId, associationId), subscriptionMapper).stream().findFirst();
    }

    @Override
    public SubscriptionView createSubscription(
            UUID userId, UUID associationId, SubscriptionRequest request) {
        return jdbc.queryForObject("""
                INSERT INTO notification_subscription (
                    user_id, association_id, subscription_type, filters, channels, status)
                VALUES (:userId, :associationId, :type, CAST(:filters AS jsonb),
                        CAST(:channels AS jsonb), 'ACTIVE')
                RETURNING id, user_id, association_id, subscription_type, filters::text AS filters,
                          channels::text AS channels, status, version, created_at, updated_at
                """, requestParams(userId, associationId, request), subscriptionMapper);
    }

    @Override
    public Optional<SubscriptionView> updateSubscription(
            UUID id, UUID userId, UUID associationId, long expectedVersion, SubscriptionRequest request) {
        return jdbc.query("""
                UPDATE notification_subscription
                   SET subscription_type = :type, filters = CAST(:filters AS jsonb),
                       channels = CAST(:channels AS jsonb), updated_at = now(), version = version + 1
                 WHERE id = :id AND user_id = :userId AND association_id = :associationId
                   AND version = :version
                RETURNING id, user_id, association_id, subscription_type, filters::text AS filters,
                          channels::text AS channels, status, version, created_at, updated_at
                """, requestParams(userId, associationId, request).addValue("id", id)
                .addValue("version", expectedVersion), subscriptionMapper).stream().findFirst();
    }

    @Override
    public Optional<SubscriptionView> changeSubscriptionStatus(
            UUID id, UUID userId, UUID associationId, long expectedVersion, String status) {
        return jdbc.query("""
                UPDATE notification_subscription
                   SET status = :status, updated_at = now(), version = version + 1
                 WHERE id = :id AND user_id = :userId AND association_id = :associationId
                   AND version = :version
                RETURNING id, user_id, association_id, subscription_type, filters::text AS filters,
                          channels::text AS channels, status, version, created_at, updated_at
                """, ids(id, userId, associationId).addValue("version", expectedVersion)
                .addValue("status", status),
                subscriptionMapper).stream().findFirst();
    }

    @Override
    public boolean deleteSubscription(UUID id, UUID userId, UUID associationId, long expectedVersion) {
        return jdbc.update("""
                DELETE FROM notification_subscription
                 WHERE id = :id AND user_id = :userId AND association_id = :associationId
                   AND version = :version
                """, ids(id, userId, associationId).addValue("version", expectedVersion)) == 1;
    }

    @Override
    public List<NotificationMessageView> messages(
            UUID userId, UUID associationId, boolean unreadOnly, int offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("associationId", associationId)
                .addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(MESSAGE_SELECT + " WHERE user_id = :userId"
                        + readAssociationClause(associationId)
                        + (unreadOnly ? " AND read_at IS NULL" : "")
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                params, messageMapper);
    }

    @Override
    public long countMessages(UUID userId, UUID associationId, boolean unreadOnly) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification_message WHERE user_id = :userId"
                        + readAssociationClause(associationId)
                        + (unreadOnly ? " AND read_at IS NULL" : ""),
                new MapSqlParameterSource("userId", userId).addValue("associationId", associationId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<NotificationMessageView> message(UUID id, UUID userId, UUID associationId) {
        return jdbc.query(MESSAGE_SELECT
                        + " WHERE id = :id AND user_id = :userId AND association_id = :associationId",
                ids(id, userId, associationId), messageMapper).stream().findFirst();
    }

    @Override
    public Optional<NotificationMessageView> markRead(UUID id, UUID userId, UUID associationId) {
        return jdbc.query("""
                UPDATE notification_message
                   SET read_at = COALESCE(read_at, now()), status = 'READ'
                 WHERE id = :id AND user_id = :userId AND association_id = :associationId
                RETURNING id, user_id, association_id, notification_type, title, body, resource_type,
                          resource_id, status, read_at, created_at, delivered_at
                """, ids(id, userId, associationId), messageMapper).stream().findFirst();
    }

    @Override
    public boolean policyBelongsToAssociation(UUID policyId, UUID associationId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM policy_document
                     WHERE id = :policyId AND association_id = :associationId
                       AND status = 'PUBLISHED' AND disabled_at IS NULL AND deleted_at IS NULL)
                """, new MapSqlParameterSource("policyId", policyId)
                .addValue("associationId", associationId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public PolicyNotificationResult publishPolicy(
            UUID associationId, PolicyNotificationRequest request, ActorScope actor) {
        String eventKey = eventKey(associationId, request.policyId(), request.idempotencyKey());
        int recipients = Optional.ofNullable(jdbc.queryForObject("""
                SELECT count(DISTINCT s.user_id)
                  FROM notification_subscription s
                  JOIN user_account u ON u.id = s.user_id AND u.status = 'ACTIVE'
                 WHERE s.association_id = :associationId AND s.subscription_type = 'POLICY'
                   AND s.status = 'ACTIVE' AND s.channels @> '["IN_APP"]'::jsonb
                """, new MapSqlParameterSource("associationId", associationId), Integer.class)).orElse(0);
        String payload = json(Map.of("associationId", associationId.toString(),
                "policyId", request.policyId().toString(), "recipientCount", recipients,
                "actorSubject", actor.subject()));
        List<UUID> inserted = jdbc.query("""
                INSERT INTO outbox_event (
                    aggregate_type, aggregate_id, event_type, payload, idempotency_key)
                VALUES ('POLICY_DOCUMENT', :policyId, 'POLICY_NOTIFICATION_PUBLISHED',
                        CAST(:payload AS jsonb), :idempotencyKey)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """, new MapSqlParameterSource("policyId", request.policyId())
                .addValue("payload", payload).addValue("idempotencyKey", eventKey),
                (rs, row) -> rs.getObject("id", UUID.class));
        if (inserted.isEmpty()) {
            Integer previous = jdbc.queryForObject("""
                    SELECT COALESCE((payload ->> 'recipientCount')::integer, 0)
                      FROM outbox_event WHERE idempotency_key = :idempotencyKey
                    """, new MapSqlParameterSource("idempotencyKey", eventKey), Integer.class);
            return new PolicyNotificationResult(request.policyId(), associationId,
                    previous == null ? 0 : previous, true);
        }
        jdbc.update("""
                INSERT INTO notification_message (
                    user_id, association_id, notification_type, title, body,
                    resource_type, resource_id, status, idempotency_key, delivered_at)
                SELECT DISTINCT s.user_id, :associationId, 'POLICY', :title, :body,
                       'POLICY_DOCUMENT', :policyId, 'DELIVERED',
                       :idempotencyKey || ':' || s.user_id::text, now()
                  FROM notification_subscription s
                  JOIN user_account u ON u.id = s.user_id AND u.status = 'ACTIVE'
                 WHERE s.association_id = :associationId AND s.subscription_type = 'POLICY'
                   AND s.status = 'ACTIVE' AND s.channels @> '["IN_APP"]'::jsonb
                ON CONFLICT (idempotency_key) DO NOTHING
                """, new MapSqlParameterSource("associationId", associationId)
                .addValue("title", request.title()).addValue("body", request.body())
                .addValue("policyId", request.policyId())
                .addValue("idempotencyKey", eventKey));
        return new PolicyNotificationResult(request.policyId(), associationId, recipients, false);
    }

    @Override
    public void audit(
            ActorScope actor, UUID associationId, String action, String resourceType,
            UUID resourceId, Map<String, Object> details) {
        jdbc.update("""
                INSERT INTO audit_log (
                    actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                    action, resource_type, resource_id, resource_version, outcome, details, request_id)
                VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                        :actorSubject, COALESCE(:actorUsername, :actorSubject), :associationId, :enterpriseId,
                        :action, :resourceType, :resourceId, NULL, 'SUCCESS',
                        CAST(:details AS jsonb), COALESCE(:requestId, 'internal'))
                """, new MapSqlParameterSource("actorUserId", actor.userId())
                .addValue("actorSubject", actor.subject()).addValue("actorUsername", actor.username())
                .addValue("associationId", associationId).addValue("enterpriseId", actor.enterpriseId())
                .addValue("action", action).addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId.toString()).addValue("details", json(details))
                .addValue("requestId", MDC.get("requestId")));
    }

    private MapSqlParameterSource requestParams(
            UUID userId, UUID associationId, SubscriptionRequest request) {
        return new MapSqlParameterSource("userId", userId).addValue("associationId", associationId)
                .addValue("type", request.subscriptionType())
                .addValue("filters", json(request.filters())).addValue("channels", json(request.channels()));
    }

    private static MapSqlParameterSource ids(UUID id, UUID userId, UUID associationId) {
        return new MapSqlParameterSource("id", id).addValue("userId", userId)
                .addValue("associationId", associationId);
    }

    private static String readAssociationClause(UUID associationId) {
        return associationId == null ? "" : " AND association_id = :associationId";
    }

    private SubscriptionView mapSubscription(ResultSet rs, int row) throws SQLException {
        return new SubscriptionView(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("association_id", UUID.class), rs.getString("subscription_type"),
                read(rs.getString("filters"), MAP), read(rs.getString("channels"), STRINGS),
                rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private NotificationMessageView mapMessage(ResultSet rs, int row) throws SQLException {
        return new NotificationMessageView(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("association_id", UUID.class),
                rs.getString("notification_type"), rs.getString("title"), rs.getString("body"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class), rs.getString("status"),
                instant(rs, "read_at"), rs.getTimestamp("created_at").toInstant(), instant(rs, "delivered_at"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("notification data could not be serialized", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) throws SQLException {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException("stored notification data is invalid JSON", exception);
        }
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String eventKey(UUID associationId, UUID policyId, String clientKey) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((associationId + ":" + policyId + ":" + clientKey)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "policy-notification:" + java.util.HexFormat.of().formatHex(value);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
