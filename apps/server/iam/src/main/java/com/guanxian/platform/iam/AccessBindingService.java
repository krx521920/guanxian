package com.guanxian.platform.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.error.PreconditionRequiredException;
import com.guanxian.platform.shared.security.ActorScope;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class AccessBindingService {
    private static final String VIEW_SELECT = """
            SELECT account.id, account.external_subject, account.username, account.display_name,
                   account.email,
                   account.association_id, association.name AS association_name,
                   account.enterprise_id, enterprise.name AS enterprise_name, account.status,
                   account.version, account.updated_at
            FROM user_account AS account
            LEFT JOIN association ON association.id = account.association_id
            LEFT JOIN enterprise ON enterprise.id = account.enterprise_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    AccessBindingService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<AccessBindingView> findAll(ActorScope actor) {
        requireSystemAdministrator(actor);
        if (actor.associationId() == null && actor.enterpriseId() != null) {
            throw scopeViolation("enterprise context requires an association context");
        }
        StringBuilder scope = new StringBuilder();
        if (actor.associationId() != null) {
            scope.append(" WHERE account.association_id=:associationId");
            if (actor.enterpriseId() != null) {
                scope.append(" AND account.enterprise_id=:enterpriseId");
            }
        }
        return jdbc.query(VIEW_SELECT + scope + " ORDER BY account.display_name, account.id",
                contextParameters(actor), this::view);
    }

    AccessBindingPage page(ActorScope actor, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<AccessBindingView> all = findAll(actor);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new AccessBindingPage(List.copyOf(all.subList(from, to)), all.size(), safePage, safeSize);
    }

    @Transactional
    AccessBindingView upsert(AccessBindingRequest request, Long expectedVersion, ActorScope actor) {
        requireWriteContext(actor);
        requireRequestedScope(actor, request.associationId(), request.enterpriseId());
        if (request.externalSubject().trim().equals(actor.subject())
                || request.username().trim().equals(actor.username())) {
            throw scopeViolation("system administrators cannot change their own access binding");
        }
        UUID associationId = resolveAssociation(request.associationId(), request.enterpriseId());
        requireTargetScope(actor, associationId, request.enterpriseId());
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
                SELECT id, external_subject, username, status, version, association_id, enterprise_id
                FROM user_account
                WHERE username = :username OR external_subject = :externalSubject
                FOR UPDATE
                """, parameters, (rs, row) -> new ExistingAccount(
                rs.getObject("id", UUID.class), rs.getString("external_subject"),
                rs.getString("username"), rs.getString("status"), rs.getLong("version"),
                rs.getObject("association_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class)));
        if (matches.size() > 1) {
            throw new ConflictException("username and external subject belong to different accounts");
        }
        ExistingAccount existing = matches.isEmpty() ? null : matches.getFirst();
        if (existing != null && existing.externalSubject() != null
                && !externalSubject.equals(existing.externalSubject())) {
            throw new ConflictException("user account is already bound to another external subject");
        }
        UUID accountId = existing == null ? UUID.randomUUID() : existing.id();
        if (existing != null) {
            requireExistingScope(actor, existing);
            requireNotSelf(actor, existing.id(), existing.externalSubject(), existing.username());
            requireVersion(expectedVersion, existing.version());
        }
        parameters.addValue("id", accountId);
        long newVersion = existing == null ? 0 : existing.version() + 1;
        parameters.addValue("newVersion", newVersion);
        try {
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO user_account (
                            id, association_id, enterprise_id, external_subject, username,
                            display_name, email, status, version, created_at, updated_at)
                        VALUES (:id, :associationId, :enterpriseId, :externalSubject, :username,
                                :displayName, :email, 'ACTIVE', 0, now(), now())
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
                            version = :newVersion,
                            updated_at = now()
                        WHERE id = :id
                        """, parameters);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("username or external subject is already bound");
        }
        jdbc.update("DELETE FROM revoked_identity_subject WHERE external_subject=:externalSubject", parameters);
        String status = existing == null ? "ACTIVE" : existing.status();
        recordAudit(actor, "ACCESS_BINDING_UPSERT", accountId, externalSubject, associationId,
                request.enterpriseId(), newVersion, status,
                Map.of("externalSubject", externalSubject, "bindingStatus", status));
        return findById(accountId);
    }

    @Transactional
    AccessBindingView disable(UUID id, long expectedVersion, ActorScope actor) {
        requireWriteContext(actor);
        AccountState existing = stateForUpdate(id);
        requireTargetScope(actor, existing.associationId(), existing.enterpriseId());
        requireNotSelf(actor, existing.id(), existing.externalSubject(), existing.username());
        requireVersion(expectedVersion, existing.version());
        if (!"ACTIVE".equals(existing.status())) {
            throw new ConflictException("access binding is already inactive");
        }
        long newVersion = existing.version() + 1;
        jdbc.update("""
                UPDATE user_account
                   SET status='INACTIVE', version=:newVersion, updated_at=now()
                 WHERE id=:id
                """, new MapSqlParameterSource("id", id).addValue("newVersion", newVersion));
        recordAudit(actor, "ACCESS_BINDING_DISABLE", id, existing.externalSubject(),
                existing.associationId(), existing.enterpriseId(), newVersion, "INACTIVE",
                transitionDetails(existing.status(), "INACTIVE", existing.externalSubject()));
        return findById(id);
    }

    @Transactional
    AccessBindingView restore(UUID id, long expectedVersion, ActorScope actor) {
        requireWriteContext(actor);
        AccountState existing = stateForUpdate(id);
        requireTargetScope(actor, existing.associationId(), existing.enterpriseId());
        requireNotSelf(actor, existing.id(), existing.externalSubject(), existing.username());
        requireVersion(expectedVersion, existing.version());
        if ("ACTIVE".equals(existing.status())) {
            throw new ConflictException("access binding is already active");
        }
        if (existing.externalSubject() == null || existing.externalSubject().isBlank()) {
            throw invalidBinding("unbound account must be assigned an external subject before it can be restored");
        }
        ensureScopeIsEligible(existing);
        long newVersion = existing.version() + 1;
        jdbc.update("""
                UPDATE user_account
                   SET status='ACTIVE', version=:newVersion, updated_at=now()
                 WHERE id=:id
                """, new MapSqlParameterSource("id", id).addValue("newVersion", newVersion));
        recordAudit(actor, "ACCESS_BINDING_RESTORE", id, existing.externalSubject(),
                existing.associationId(), existing.enterpriseId(), newVersion, "ACTIVE",
                transitionDetails(existing.status(), "ACTIVE", existing.externalSubject()));
        return findById(id);
    }

    @Transactional
    AccessBindingView unbind(UUID id, long expectedVersion, ActorScope actor) {
        requireWriteContext(actor);
        AccountState existing = stateForUpdate(id);
        requireTargetScope(actor, existing.associationId(), existing.enterpriseId());
        requireNotSelf(actor, existing.id(), existing.externalSubject(), existing.username());
        requireVersion(expectedVersion, existing.version());
        if (existing.externalSubject() == null || existing.externalSubject().isBlank()) {
            throw new ConflictException("account is already unbound");
        }
        long newVersion = existing.version() + 1;
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id)
                .addValue("newVersion", newVersion)
                .addValue("externalSubject", existing.externalSubject())
                .addValue("revokedBySubject", actor.subject());
        jdbc.update("DELETE FROM revoked_identity_subject WHERE external_subject=:externalSubject", parameters);
        jdbc.update("""
                INSERT INTO revoked_identity_subject (
                    external_subject, user_account_id, revoked_by_subject, reason, revoked_at)
                VALUES (:externalSubject, :id, :revokedBySubject, 'UNBOUND', now())
                """, parameters);
        jdbc.update("""
                UPDATE user_account
                   SET external_subject=NULL, status='INACTIVE', version=:newVersion, updated_at=now()
                 WHERE id=:id
                """, parameters);
        recordAudit(actor, "ACCESS_BINDING_UNBIND", id, existing.externalSubject(),
                existing.associationId(), existing.enterpriseId(), newVersion, "INACTIVE",
                transitionDetails(existing.status(), "INACTIVE", existing.externalSubject()));
        return findById(id);
    }

    private UUID resolveAssociation(UUID requestedAssociationId, UUID enterpriseId) {
        if (enterpriseId != null) {
            List<UUID> associations = jdbc.queryForList(
                    """
                    SELECT enterprise.association_id
                      FROM enterprise
                      JOIN association ON association.id=enterprise.association_id
                     WHERE enterprise.id=:enterpriseId
                       AND enterprise.status NOT IN ('DISABLED', 'DELETED')
                       AND enterprise.deleted_at IS NULL
                       AND association.status='ACTIVE'
                    """,
                    new MapSqlParameterSource("enterpriseId", enterpriseId), UUID.class);
            if (associations.size() != 1) {
                throw invalidBinding("enterprise does not exist or is not eligible for access binding");
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
                "SELECT COUNT(*) FROM association WHERE id=:associationId AND status='ACTIVE'",
                new MapSqlParameterSource("associationId", requestedAssociationId), Integer.class);
        if (count == null || count != 1) {
            throw invalidBinding("association does not exist or is not active");
        }
        return requestedAssociationId;
    }

    private AccessBindingView findById(UUID id) {
        List<AccessBindingView> values = jdbc.query(
                VIEW_SELECT + " WHERE account.id=:id",
                new MapSqlParameterSource("id", id), this::view);
        if (values.size() != 1) {
            throw new NotFoundException("access binding", id);
        }
        return values.getFirst();
    }

    private AccountState stateForUpdate(UUID id) {
        List<AccountState> values = jdbc.query("""
                SELECT id, external_subject, username, status, version, association_id, enterprise_id
                  FROM user_account
                 WHERE id=:id
                 FOR UPDATE
                """, new MapSqlParameterSource("id", id), (rs, row) -> new AccountState(
                rs.getObject("id", UUID.class), rs.getString("external_subject"), rs.getString("username"),
                rs.getString("status"), rs.getLong("version"), rs.getObject("association_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class)));
        if (values.size() != 1) {
            throw new NotFoundException("access binding", id);
        }
        return values.getFirst();
    }

    private void ensureScopeIsEligible(AccountState state) {
        if (state.associationId() == null) {
            throw invalidBinding("bound association is missing");
        }
        resolveAssociation(state.associationId(), state.enterpriseId());
    }

    private static void requireSystemAdministrator(ActorScope actor) {
        if (actor == null || !actor.isSystemAdmin()) {
            throw scopeViolation("system administrator identity is required");
        }
    }

    private static void requireWriteContext(ActorScope actor) {
        requireSystemAdministrator(actor);
        if (actor.associationId() == null) {
            throw new ForbiddenException(
                    "ASSOCIATION_CONTEXT_REQUIRED",
                    "system administrators must select an association before changing access bindings");
        }
    }

    private static void requireRequestedScope(
            ActorScope actor, UUID requestedAssociationId, UUID requestedEnterpriseId) {
        if (requestedAssociationId != null && !requestedAssociationId.equals(actor.associationId())) {
            throw scopeViolation("request association cannot override the selected system context");
        }
        if (actor.enterpriseId() != null && !actor.enterpriseId().equals(requestedEnterpriseId)) {
            throw scopeViolation("request enterprise cannot override the selected system context");
        }
    }

    private static void requireTargetScope(
            ActorScope actor, UUID associationId, UUID enterpriseId) {
        if (!actor.associationId().equals(associationId)
                || actor.enterpriseId() != null && !actor.enterpriseId().equals(enterpriseId)) {
            throw scopeViolation("access binding is outside the selected system context");
        }
    }

    private static void requireExistingScope(ActorScope actor, ExistingAccount existing) {
        if (existing.associationId() == null && existing.enterpriseId() == null
                && existing.externalSubject() == null) {
            return;
        }
        requireTargetScope(actor, existing.associationId(), existing.enterpriseId());
    }

    private static void requireNotSelf(
            ActorScope actor, UUID accountId, String externalSubject, String username) {
        if (accountId.equals(actor.userId())
                || externalSubject != null && externalSubject.equals(actor.subject())
                || username != null && username.equals(actor.username())) {
            throw scopeViolation("system administrators cannot change their own access binding");
        }
    }

    private static MapSqlParameterSource contextParameters(ActorScope actor) {
        return new MapSqlParameterSource()
                .addValue("associationId", actor.associationId())
                .addValue("enterpriseId", actor.enterpriseId());
    }

    private AccessBindingView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String subject = rs.getString("external_subject");
        return new AccessBindingView(
                rs.getObject("id", UUID.class), subject, rs.getString("username"),
                rs.getString("display_name"), rs.getString("email"),
                rs.getObject("association_id", UUID.class),
                rs.getString("association_name"), rs.getObject("enterprise_id", UUID.class),
                rs.getString("enterprise_name"), rs.getString("status"), rs.getLong("version"),
                subject != null && !subject.isBlank(), rs.getTimestamp("updated_at").toInstant());
    }

    private static void requireVersion(Long expectedVersion, long actualVersion) {
        if (expectedVersion == null) {
            throw new PreconditionRequiredException("If-Match header is required when updating an access binding");
        }
        if (expectedVersion != actualVersion) {
            throw new PreconditionFailedException("access binding version is stale; reload and retry");
        }
    }

    private static Map<String, Object> transitionDetails(
            String previousStatus, String newStatus, String previousExternalSubject) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousStatus", previousStatus);
        details.put("newStatus", newStatus);
        details.put("previousExternalSubject", previousExternalSubject);
        return details;
    }

    private void recordAudit(
            ActorScope actor,
            String action,
            UUID accountId,
            String subject,
            UUID associationId,
            UUID enterpriseId,
            long version,
            String status,
            Map<String, Object> extraDetails) {
        try {
            Map<String, Object> details = new LinkedHashMap<>(extraDetails);
            details.put("accountId", accountId);
            details.put("externalSubject", subject);
            details.put("status", status);
            jdbc.update("""
                    INSERT INTO audit_log (
                        actor_user_id, actor_subject, actor_username, association_id, enterprise_id,
                        action, resource_type, resource_id, resource_version, outcome, details, request_id)
                    VALUES ((SELECT id FROM user_account WHERE id = :actorUserId),
                            :actorSubject, :actorUsername, :associationId, :enterpriseId,
                            :action, 'USER_ACCOUNT', :resourceId,
                            :resourceVersion, 'SUCCESS', CAST(:details AS jsonb), :requestId)
                    """, new MapSqlParameterSource()
                    .addValue("actorUserId", actor.userId())
                    .addValue("actorSubject", actor.subject())
                    .addValue("actorUsername", actor.username() == null || actor.username().isBlank()
                            ? actor.subject() : actor.username())
                    .addValue("associationId", associationId)
                    .addValue("enterpriseId", enterpriseId)
                    .addValue("action", action)
                    .addValue("resourceId", accountId.toString())
                    .addValue("resourceVersion", version)
                    .addValue("details", objectMapper.writeValueAsString(details))
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

    private static ForbiddenException scopeViolation(String message) {
        return new ForbiddenException("ACCESS_BINDING_SCOPE_VIOLATION", message);
    }

    private record ExistingAccount(
            UUID id,
            String externalSubject,
            String username,
            String status,
            long version,
            UUID associationId,
            UUID enterpriseId) {
    }

    private record AccountState(
            UUID id,
            String externalSubject,
            String username,
            String status,
            long version,
            UUID associationId,
            UUID enterpriseId) {
    }
}
