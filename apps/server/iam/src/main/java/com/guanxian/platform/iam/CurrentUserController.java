package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
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

    public CurrentUserController(ActorScopeResolver actorScopeResolver) {
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping("/me")
    ApiResponse<CurrentUserView> me(Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        Map<String, Object> claims = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaims()
                : Map.of();
        String username = claim(claims, "preferred_username", authentication.getName());
        String displayName = claim(claims, "name", username);
        String organization = claim(claims, "organization", "");
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
