package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "jwt", matchIfMissing = true)
class DatabaseActorScopeResolver implements ActorScopeResolver {
    private final NamedParameterJdbcTemplate jdbc;

    DatabaseActorScopeResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ActorScope resolve(Authentication authentication) {
        String subject = ActorScopes.subject(authentication);
        Set<String> roles = ActorScopes.roles(authentication);
        if (roles.contains("SYSTEM_ADMIN")) {
            return new ActorScope(null, subject, authentication.getName(), null, null, roles, Set.of());
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
                  AND (source_association_id = :associationId OR target_association_id = :associationId)
                """, new MapSqlParameterSource("associationId", binding.associationId()), UUID.class));
        return new ActorScope(
                binding.userId(), subject, authentication.getName(), binding.associationId(),
                binding.enterpriseId(), roles, partners);
    }

    private static ForbiddenException incompleteBinding() {
        return new ForbiddenException("IDENTITY_SCOPE_INCOMPLETE", "authenticated identity has incomplete data scope");
    }

    private record Binding(UUID userId, UUID associationId, UUID enterpriseId) {
    }
}
