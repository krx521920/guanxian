package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class DatabaseActorScopeResolver implements ActorScopeResolver {
    static final String ASSOCIATION_CONTEXT_HEADER = "X-Guanxian-Association-Id";
    static final String ENTERPRISE_CONTEXT_HEADER = "X-Guanxian-Enterprise-Id";
    private final NamedParameterJdbcTemplate jdbc;
    private final HttpServletRequest request;
    private final Set<String> bootstrapSystemAdminSubjects;

    DatabaseActorScopeResolver(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, null, Set.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    DatabaseActorScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            HttpServletRequest request,
            @Value("${guanxian.security.jwt.bootstrap-system-admin-subjects:}")
            String bootstrapSystemAdminSubjects) {
        this(jdbc, request, parseBootstrapSubjects(bootstrapSystemAdminSubjects));
    }

    DatabaseActorScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            HttpServletRequest request,
            Set<String> bootstrapSystemAdminSubjects) {
        this.jdbc = jdbc;
        this.request = request;
        this.bootstrapSystemAdminSubjects = Set.copyOf(bootstrapSystemAdminSubjects);
    }

    @Override
    public ActorScope resolve(Authentication authentication) {
        String subject = ActorScopes.subject(authentication);
        Set<String> roles = ActorScopes.roles(authentication);
        if (roles.contains("SYSTEM_ADMIN")) {
            if (exists("""
                    SELECT EXISTS (
                        SELECT 1 FROM revoked_identity_subject WHERE external_subject=:id
                    )
                    """, subject)) {
                throw identityNotBound();
            }
            List<SystemAccount> accounts = jdbc.query("""
                    SELECT id, status
                      FROM user_account
                     WHERE external_subject=:subject
                    """, new MapSqlParameterSource("subject", subject), (rs, row) -> new SystemAccount(
                    rs.getObject("id", UUID.class), rs.getString("status")));
            if (accounts.size() > 1 || (accounts.size() == 1 && !"ACTIVE".equals(accounts.getFirst().status()))) {
                throw identityNotBound();
            }
            if (accounts.isEmpty() && !bootstrapSystemAdminSubjects.contains(subject)) {
                throw identityNotBound();
            }
            return systemAdministrator(
                    accounts.isEmpty() ? null : accounts.getFirst().id(),
                    subject, authentication.getName(), roles);
        }

        List<Binding> bindings = jdbc.query("""
                SELECT account.id,
                       account.status AS account_status,
                       account.association_id AS account_association_id,
                       account.enterprise_id,
                       enterprise.association_id AS enterprise_association_id,
                       enterprise.status AS enterprise_status,
                       enterprise.deleted_at AS enterprise_deleted_at,
                       association.status AS association_status
                FROM user_account AS account
                LEFT JOIN enterprise ON enterprise.id = account.enterprise_id
                LEFT JOIN association
                  ON association.id = COALESCE(account.association_id, enterprise.association_id)
                WHERE account.external_subject = :subject
                """, new MapSqlParameterSource("subject", subject), (rs, row) -> new Binding(
                rs.getObject("id", UUID.class),
                rs.getString("account_status"),
                rs.getObject("account_association_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class),
                rs.getObject("enterprise_association_id", UUID.class),
                rs.getString("enterprise_status"),
                rs.getTimestamp("enterprise_deleted_at") == null
                        ? null : rs.getTimestamp("enterprise_deleted_at").toInstant(),
                rs.getString("association_status")));
        if (bindings.size() != 1 || !"ACTIVE".equals(bindings.getFirst().accountStatus())) {
            throw identityNotBound();
        }
        Binding binding = bindings.getFirst();
        UUID associationId = binding.resolvedAssociationId();
        if (binding.accountAssociationId() != null && binding.enterpriseAssociationId() != null
                && !binding.accountAssociationId().equals(binding.enterpriseAssociationId())) {
            throw incompleteBinding();
        }
        if ((roles.contains("ASSOCIATION_ADMIN") || roles.contains("ASSOCIATION_OPERATOR"))
                && associationId == null) {
            throw incompleteBinding();
        }
        if ((roles.contains("ENTERPRISE_ADMIN") || roles.contains("ENTERPRISE_MEMBER"))
                && (associationId == null || binding.enterpriseId() == null)) {
            throw incompleteBinding();
        }
        if (associationId != null && !"ACTIVE".equals(binding.associationStatus())) {
            throw inactiveScope("bound association is not active");
        }
        if (binding.enterpriseId() != null
                && (binding.enterpriseAssociationId() == null
                || binding.enterpriseDeletedAt() != null
                || "DISABLED".equals(binding.enterpriseStatus())
                || "DELETED".equals(binding.enterpriseStatus()))) {
            throw inactiveScope("bound enterprise is disabled or deleted");
        }
        Set<UUID> partners = associationId == null ? Set.of() : Set.copyOf(jdbc.queryForList("""
                SELECT CASE
                         WHEN source_association_id = :associationId THEN target_association_id
                         ELSE source_association_id
                       END AS partner_id
                FROM association_relationship
                WHERE status = 'ACTIVE'
                  AND allow_member_data = TRUE
                  AND suspended_at IS NULL
                  AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > now())
                  AND (source_association_id = :associationId OR target_association_id = :associationId)
                  AND EXISTS (
                      SELECT 1
                        FROM association AS partner
                       WHERE partner.id = CASE
                                 WHEN source_association_id = :associationId THEN target_association_id
                                 ELSE source_association_id
                             END
                         AND partner.status = 'ACTIVE'
                  )
                """, new MapSqlParameterSource("associationId", associationId), UUID.class));
        return new ActorScope(
                binding.userId(), subject, authentication.getName(), associationId,
                binding.enterpriseId(), roles, partners);
    }

    private ActorScope systemAdministrator(UUID userId, String subject, String username, Set<String> roles) {
        UUID associationId = contextId(ASSOCIATION_CONTEXT_HEADER);
        UUID enterpriseId = contextId(ENTERPRISE_CONTEXT_HEADER);
        if (enterpriseId != null && associationId == null) {
            throw invalidContext("enterprise context requires an association context");
        }
        if (associationId != null && !exists("""
                SELECT EXISTS (SELECT 1 FROM association WHERE id=:id AND status='ACTIVE')
                """, associationId)) {
            throw new ForbiddenException(
                    "SYSTEM_CONTEXT_FORBIDDEN", "selected association context is not active");
        }
        if (enterpriseId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM enterprise
                     WHERE id=:enterpriseId
                       AND association_id=:associationId
                       AND status = 'ACTIVE'
                       AND deleted_at IS NULL)
                """, new MapSqlParameterSource()
                .addValue("enterpriseId", enterpriseId)
                .addValue("associationId", associationId), Boolean.class))) {
            throw new ForbiddenException(
                    "SYSTEM_CONTEXT_FORBIDDEN",
                    "selected enterprise does not belong to the selected association");
        }
        return new ActorScope(
                userId, subject, username, associationId, enterpriseId, roles, Set.of());
    }

    private UUID contextId(String header) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw invalidContext(header + " must contain one UUID");
        }
    }

    private boolean exists(String sql, Object id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                sql, new MapSqlParameterSource("id", id), Boolean.class));
    }

    private static Set<String> parseBootstrapSubjects(String configuredSubjects) {
        if (configuredSubjects == null || configuredSubjects.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configuredSubjects.split(","))
                .map(String::trim)
                .filter(subject -> !subject.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static ForbiddenException identityNotBound() {
        return new ForbiddenException(
                "IDENTITY_NOT_BOUND", "authenticated identity is not bound to platform data");
    }

    private static ApiException invalidContext(String message) {
        return new ApiException("INVALID_SYSTEM_CONTEXT", message, HttpStatus.BAD_REQUEST);
    }

    private static ForbiddenException incompleteBinding() {
        return new ForbiddenException("IDENTITY_SCOPE_INCOMPLETE", "authenticated identity has incomplete data scope");
    }

    private static ForbiddenException inactiveScope(String message) {
        return new ForbiddenException("IDENTITY_SCOPE_INACTIVE", message);
    }

    private record Binding(
            UUID userId,
            String accountStatus,
            UUID accountAssociationId,
            UUID enterpriseId,
            UUID enterpriseAssociationId,
            String enterpriseStatus,
            Instant enterpriseDeletedAt,
            String associationStatus) {

        UUID resolvedAssociationId() {
            return accountAssociationId == null ? enterpriseAssociationId : accountAssociationId;
        }
    }

    private record SystemAccount(UUID id, String status) {
    }
}
