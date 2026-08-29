package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;

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

    DatabaseActorScopeResolver(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    DatabaseActorScopeResolver(
            NamedParameterJdbcTemplate jdbc,
            HttpServletRequest request) {
        this.jdbc = jdbc;
        this.request = request;
    }

    @Override
    public ActorScope resolve(Authentication authentication) {
        String subject = ActorScopes.subject(authentication);
        Set<String> roles = ActorScopes.roles(authentication);
        if (roles.contains("SYSTEM_ADMIN")) {
            return systemAdministrator(subject, authentication.getName(), roles);
        }

        List<Binding> bindings = jdbc.query("""
                SELECT account.id,
                       COALESCE(account.association_id, enterprise.association_id) AS association_id,
                       account.enterprise_id
                FROM user_account AS account
                LEFT JOIN enterprise ON enterprise.id = account.enterprise_id
                WHERE account.external_subject = :subject
                  AND account.status = 'ACTIVE'
                """, new MapSqlParameterSource("subject", subject), (rs, row) -> new Binding(
                rs.getObject("id", UUID.class),
                rs.getObject("association_id", UUID.class),
                rs.getObject("enterprise_id", UUID.class)));
        if (bindings.size() != 1) {
            throw new ForbiddenException("IDENTITY_NOT_BOUND", "authenticated identity is not bound to platform data");
        }
        Binding binding = bindings.getFirst();
        if ((roles.contains("ASSOCIATION_ADMIN") || roles.contains("ASSOCIATION_OPERATOR"))
                && binding.associationId() == null) {
            throw incompleteBinding();
        }
        if ((roles.contains("ENTERPRISE_ADMIN") || roles.contains("ENTERPRISE_MEMBER"))
                && (binding.associationId() == null || binding.enterpriseId() == null)) {
            throw incompleteBinding();
        }
        Set<UUID> partners = binding.associationId() == null ? Set.of() : Set.copyOf(jdbc.queryForList("""
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
                """, new MapSqlParameterSource("associationId", binding.associationId()), UUID.class));
        return new ActorScope(
                binding.userId(), subject, authentication.getName(), binding.associationId(),
                binding.enterpriseId(), roles, partners);
    }

    private ActorScope systemAdministrator(String subject, String username, Set<String> roles) {
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
                       AND status <> 'DELETED')
                """, new MapSqlParameterSource()
                .addValue("enterpriseId", enterpriseId)
                .addValue("associationId", associationId), Boolean.class))) {
            throw new ForbiddenException(
                    "SYSTEM_CONTEXT_FORBIDDEN",
                    "selected enterprise does not belong to the selected association");
        }
        return new ActorScope(
                null, subject, username, associationId, enterpriseId, roles, Set.of());
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

    private boolean exists(String sql, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                sql, new MapSqlParameterSource("id", id), Boolean.class));
    }

    private static ApiException invalidContext(String message) {
        return new ApiException("INVALID_SYSTEM_CONTEXT", message, HttpStatus.BAD_REQUEST);
    }

    private static ForbiddenException incompleteBinding() {
        return new ForbiddenException("IDENTITY_SCOPE_INCOMPLETE", "authenticated identity has incomplete data scope");
    }

    private record Binding(UUID userId, UUID associationId, UUID enterpriseId) {
    }
}
