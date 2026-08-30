package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class CurrentUserController {
    private final ActorScopeResolver actorScopeResolver;
    private final NamedParameterJdbcTemplate jdbc;

    public CurrentUserController(
            ActorScopeResolver actorScopeResolver,
            NamedParameterJdbcTemplate jdbc) {
        this.actorScopeResolver = actorScopeResolver;
        this.jdbc = jdbc;
    }

    @GetMapping("/me")
    ApiResponse<CurrentUserView> me(Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        Map<String, Object> claims = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaims()
                : Map.of();
        String username = claim(claims, "preferred_username", authentication.getName());
        String displayName = claim(claims, "name", username);
        String organization = claim(claims, "organization", organizationName(actor));
        String title = claim(claims, "title", "");
        List<String> roles = actor.roles().stream().sorted().toList();
        List<String> permissions = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> !authority.startsWith("ROLE_"))
                .sorted()
                .toList();
        return ApiResponse.ok(new CurrentUserView(
                actor.subject(), username, displayName, organization, title, roles, permissions,
                actor.associationId(), actor.enterpriseId()));
    }

    private static String claim(Map<String, Object> claims, String name, String fallback) {
        Object value = claims.get(name);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private String organizationName(ActorScope actor) {
        try {
            if (actor.enterpriseId() != null) {
                List<String> names = jdbc.queryForList(
                        """
                        SELECT name FROM enterprise
                         WHERE id=:id
                           AND status NOT IN ('DISABLED', 'DELETED')
                           AND deleted_at IS NULL
                        """,
                        new MapSqlParameterSource("id", actor.enterpriseId()), String.class);
                if (!names.isEmpty()) {
                    return names.getFirst();
                }
            }
            if (actor.associationId() != null) {
                List<String> names = jdbc.queryForList(
                        "SELECT name FROM association WHERE id=:id AND status='ACTIVE'",
                        new MapSqlParameterSource("id", actor.associationId()), String.class);
                if (!names.isEmpty()) {
                    return names.getFirst();
                }
            }
        } catch (DataAccessException ignored) {
            // Demo/test profiles may intentionally run without the persistent identity schema.
        }
        return actor.isSystemAdmin() ? "全平台" : "";
    }

    record CurrentUserView(
            String subject,
            String username,
            String displayName,
            String organization,
            String title,
            List<String> roles,
            List<String> permissions,
            UUID associationId,
            UUID enterpriseId) {
    }
}
