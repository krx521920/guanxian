package com.guanxian.platform.iam;

import com.guanxian.platform.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        return ApiResponse.ok(new CurrentUserView(authentication.getName(), roles, permissions));
    }

    record CurrentUserView(String username, List<String> roles, List<String> permissions) {
    }
}
