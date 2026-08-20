package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class CurrentUserController {

    @GetMapping("/me")
    ApiResponse<CurrentUserView> currentUser(Authentication authentication) {
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        List<String> roles = authorities.stream()
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();
        List<String> permissions = authorities.stream()
                .filter(authority -> !authority.startsWith("ROLE_"))
                .toList();

        Map<String, Object> claims = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getTokenAttributes()
                : Map.of();
        String username = textClaim(claims, "preferred_username", authentication.getName());
        String subject = textClaim(claims, "sub", username);
        String displayName = textClaim(claims, "name", username);
        String organization = textClaim(claims, "organization", "");
        String title = textClaim(claims, "title", roles.isEmpty() ? "" : roles.getFirst());

        return ApiResponse.ok(new CurrentUserView(
                subject, username, displayName, organization, title, roles, permissions));
    }

    private static String textClaim(Map<String, Object> claims, String name, String fallback) {
        Object value = claims.get(name);
        return value instanceof String text && !text.isBlank() ? text.trim() : fallback;
    }

    record CurrentUserView(
            String subject,
            String username,
            String displayName,
            String organization,
            String title,
            List<String> roles,
            List<String> permissions) {
    }
}
