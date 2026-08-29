package com.guanxian.platform.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class AccessBindingService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    AccessBindingService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<AccessBindingView> findAll() {
        return jdbc.query("""
                SELECT account.id, account.external_subject, account.username, account.display_name,
                       account.association_id, association.name AS association_name,
                       account.enterprise_id, enterprise.name AS enterprise_name, account.status
                FROM user_account AS account
                LEFT JOIN association ON association.id = account.association_id
                LEFT JOIN enterprise ON enterprise.id = account.enterprise_id
                WHERE account.external_subject IS NOT NULL
                ORDER BY account.display_name, account.id
                """, (rs, row) -> new AccessBindingView(
                rs.getObject("id", UUID.class), rs.getString("external_subject"), rs.getString("username"),
                rs.getString("display_name"), rs.getObject("association_id", UUID.class),
                rs.getString("association_name"), rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"), rs.getString("status")));
    }

    @Transactional
    AccessBindingView upsert(AccessBindingRequest request, ActorScope actor) {
        UUID associationId = resolveAssociation(request.associationId(), request.enterpriseId());
        String externalSubject = request.externalSubject().trim();
        String username = request.username().trim();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("externalSubject", externalSubject)
                .addValue("username", username)
                .addValue("displayName", request.displayName().trim())
                .addValue("associationId", associationId)
                .addValue("enterpriseId", request.enterpriseId())
                .addValue("email", trimToNull(request.email()));
        List<ExistingAccount> matches = jdbc.query("""
                SELECT id, external_subject
                FROM user_account
                WHERE username = :username OR external_subject = :externalSubject
                FOR UPDATE
                """, parameters, (rs, row) -> new ExistingAccount(
                rs.getObject("id", UUID.class), rs.getString("external_subject")));
        if (matches.size() > 1) {
            throw new ConflictException("username and external subject belong to different accounts");
        }
        ExistingAccount existing = matches.isEmpty() ? null : matches.getFirst();
        if (existing != null && existing.externalSubject() != null
                && !externalSubject.equals(existing.externalSubject())) {
            throw new ConflictException("user account is already bound to another external subject");
        }
        UUID accountId = existing == null ? UUID.randomUUID() : existing.id();
        parameters.addValue("id", accountId);
        try {
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO user_account (
                            id, association_id, enterprise_id, external_subject, username,
                            display_name, email, status, created_at, updated_at)
                        VALUES (:id, :associationId, :enterpriseId, :externalSubject, :username,
                                :displayName, :email, 'ACTIVE', now(), now())
                        """, parameters);
            } else {
                jdbc.update("""
                        UPDATE user_account
                        SET association_id = :associationId,
                            enterprise_id = :enterpriseId,
                            external_subject = :externalSubject,
                            username = :username,
                            display_name = :displayName,
                            email = :email,
                            status = 'ACTIVE',
                            updated_at = now()
                        WHERE id = :id
                        """, parameters);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("username or external subject is already bound");
        }
        recordAudit(actor, externalSubject, associationId, request.enterpriseId());
        return jdbc.query("""
                SELECT account.id, account.external_subject, account.username, account.display_name,
                       account.association_id, association.name AS association_name,
                       account.enterprise_id, enterprise.name AS enterprise_name, account.status
                FROM user_account AS account
                LEFT JOIN association ON association.id = account.association_id
                LEFT JOIN enterprise ON enterprise.id = account.enterprise_id
                WHERE account.external_subject = :externalSubject
                """, parameters, (rs, row) -> new AccessBindingView(
                rs.getObject("id", UUID.class), rs.getString("external_subject"), rs.getString("username"),
                rs.getString("display_name"), rs.getObject("association_id", UUID.class),
                rs.getString("association_name"), rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"), rs.getString("status"))).getFirst();
    }

    private UUID resolveAssociation(UUID requestedAssociationId, UUID enterpriseId) {
        if (enterpriseId != null) {
            List<UUID> associations = jdbc.queryForList(
                    "SELECT association_id FROM enterprise WHERE id = :enterpriseId",
                    new MapSqlParameterSource("enterpriseId", enterpriseId), UUID.class);
            if (associations.size() != 1) {
                throw invalidBinding("enterprise does not exist");
            }
            UUID enterpriseAssociation = associations.getFirst();
            if (requestedAssociationId != null && !requestedAssociationId.equals(enterpriseAssociation)) {
                throw invalidBinding("enterprise does not belong to the requested association");
            }
            return enterpriseAssociation;
        }
        if (requestedAssociationId == null) {
            throw invalidBinding("associationId or enterpriseId is required");
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM association WHERE id = :associationId",
                new MapSqlParameterSource("associationId", requestedAssociationId), Integer.class);
        if (count == null || count != 1) {
            throw invalidBinding("association does not exist");
        }
        return requestedAssociationId;
    }

    private void recordAudit(ActorScope actor, String subject, UUID associationId, UUID enterpriseId) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log (
                        actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                        action, resource_type, resource_id, resource_version, outcome, details, request_id)
                    VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                            :actorSubject, :actorUsername, :associationId, :enterpriseId,
                            'ACCESS_BINDING_UPSERT', 'USER_ACCOUNT', :resourceId,
                            NULL, 'SUCCESS', CAST(:details AS jsonb), :requestId)
                    """, new MapSqlParameterSource()
                    .addValue("actorUserId", actor.userId())
                    .addValue("actorSubject", actor.subject())
                    .addValue("actorUsername", actor.username() == null || actor.username().isBlank()
                            ? actor.subject() : actor.username())
                    .addValue("associationId", associationId)
                    .addValue("enterpriseId", enterpriseId)
                    .addValue("resourceId", subject)
                    .addValue("details", objectMapper.writeValueAsString(Map.of("externalSubject", subject)))
                    .addValue("requestId", MDC.get("requestId") == null ? "internal" : MDC.get("requestId")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("access binding audit could not be serialized", exception);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalidBinding(String message) {
        return new ApiException("INVALID_ACCESS_BINDING", message, HttpStatus.BAD_REQUEST);
    }

    private record ExistingAccount(UUID id, String externalSubject) {
    }
}
