package com.guanxian.platform.iam;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigTest {
    @Test
    void productionProfilesRejectDemoAuthentication() {
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("demo", new String[]{"prod"}));
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("demo", new String[]{"PRODUCTION"}));
        assertDoesNotThrow(() -> SecurityConfig.validateSecurityMode("jwt", new String[]{"prod"}));
    }

    @Test
    void unknownAuthenticationModeFailsClosed() {
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("basic", new String[]{"dev"}));
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateSecurityMode("", new String[]{}));
    }

    @Test
    void jwtRolesAndPermissionsAreWhitelistedAndExpanded() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject")
                .claim("roles", List.of("association_admin", "UNTRUSTED_ROLE"))
                .claim("realm_access", Map.of("roles", List.of("ENTERPRISE_MEMBER")))
                .claim("permissions", List.of("POLICY_READ", "ROOT_ACCESS"))
                .build();

        List<String> authorities = SecurityConfig.authoritiesFor(jwt).stream()
                .map(authority -> authority.getAuthority())
                .toList();

        assertTrue(authorities.contains("ROLE_ASSOCIATION_ADMIN"));
        assertTrue(authorities.contains("ROLE_ENTERPRISE_MEMBER"));
        assertTrue(authorities.contains("ENTERPRISE_WRITE"));
        assertTrue(authorities.contains("DASHBOARD_ENTERPRISE_READ"));
        assertTrue(authorities.contains("POLICY_READ"));
        assertFalse(authorities.contains("ROLE_UNTRUSTED_ROLE"));
        assertFalse(authorities.contains("ROOT_ACCESS"));
    }

    @Test
    void demoUserStoreExistsOnlyForExplicitDevelopmentMode() {
        SecurityConfig config = new SecurityConfig();
        InMemoryUserDetailsManager development =
                (InMemoryUserDetailsManager) config.userDetailsService(environmentWithProfiles("dev"));

        assertTrue(development.userExists("system-admin"));
        assertTrue(development.userExists("enterprise-member"));
        assertThrows(IllegalStateException.class,
                () -> config.userDetailsService(environmentWithProfiles("production")));
    }

    @Test
    void jwtEndpointsMustBeConfiguredAndUseHttpsInProduction() {
        SecurityConfig config = new SecurityConfig();
        Environment development = environmentWithProfiles("dev");
        Environment production = environmentWithProfiles("prod");

        assertDoesNotThrow(() -> config.jwtDecoder(
                "http://localhost:8088/realms/guanxian",
                "http://localhost:8088/realms/guanxian/certs",
                development));
        assertThrows(IllegalStateException.class,
                () -> config.jwtDecoder("", "https://identity.example.com/certs", development));
        assertThrows(IllegalStateException.class,
                () -> config.jwtDecoder(
                        "http://identity.example.com/realms/guanxian",
                        "https://identity.example.com/certs",
                        production));
    }

    private static Environment environmentWithProfiles(String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return environment;
    }
}
