package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private static final Set<String> KNOWN_ROLES = Set.of(
            "SYSTEM_ADMIN",
            "ASSOCIATION_ADMIN",
            "ASSOCIATION_OPERATOR",
            "ENTERPRISE_ADMIN",
            "ENTERPRISE_MEMBER",
            "OBSERVER");
    private static final Set<String> KNOWN_PERMISSIONS = Set.of(
            "MEMBER_READ",
            "ENTERPRISE_WRITE",
            "POLICY_READ",
            "MATCH_REQUEST",
            "COLLABORATION_READ",
            "DASHBOARD_ASSOCIATION_READ",
            "DASHBOARD_ENTERPRISE_READ",
            "MEMBER_IMPORT",
            "MEMBER_REVIEW",
            "AUDIT_READ",
            "ACCESS_BINDING_WRITE",
            "NOTIFICATION_READ",
            "NOTIFICATION_PUBLISH",
            "OBSERVABILITY_READ");
    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            "SYSTEM_ADMIN", KNOWN_PERMISSIONS,
            "ASSOCIATION_ADMIN", Set.of(
                    "MEMBER_READ", "ENTERPRISE_WRITE", "POLICY_READ", "MATCH_REQUEST",
                    "COLLABORATION_READ", "DASHBOARD_ASSOCIATION_READ",
                    "MEMBER_IMPORT", "MEMBER_REVIEW", "AUDIT_READ",
                    "NOTIFICATION_READ", "NOTIFICATION_PUBLISH"),
            "ASSOCIATION_OPERATOR", Set.of(
                    "MEMBER_READ", "ENTERPRISE_WRITE", "POLICY_READ", "MATCH_REQUEST",
                    "COLLABORATION_READ", "DASHBOARD_ASSOCIATION_READ", "MEMBER_IMPORT",
                    "NOTIFICATION_READ"),
            "ENTERPRISE_ADMIN", Set.of(
                    "MEMBER_READ", "ENTERPRISE_WRITE", "POLICY_READ", "MATCH_REQUEST",
                    "COLLABORATION_READ", "DASHBOARD_ENTERPRISE_READ", "NOTIFICATION_READ"),
            "ENTERPRISE_MEMBER", Set.of(
                    "MEMBER_READ", "POLICY_READ", "MATCH_REQUEST",
                    "COLLABORATION_READ", "DASHBOARD_ENTERPRISE_READ", "NOTIFICATION_READ"),
            "OBSERVER", Set.of("MEMBER_READ", "POLICY_READ", "NOTIFICATION_READ"));

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            @Value("${guanxian.security.mode:jwt}") String configuredMode,
            Environment environment) throws Exception {
        String mode = normalizeMode(configuredMode);
        validateSecurityMode(mode, environment.getActiveProfiles());

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/actuator/health").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("OBSERVABILITY_READ")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED", "authentication is required"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                "ACCESS_DENIED", "permission denied")));

        if ("demo".equals(mode)) {
            http.httpBasic(basic -> basic.authenticationEntryPoint(
                    (request, response, exception) -> writeError(
                            response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                            "AUTHENTICATION_REQUIRED", "authentication is required")));
        } else {
            http.oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint((request, response, exception) -> writeError(
                            response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                            "AUTHENTICATION_REQUIRED", "authentication is required")));
        }
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(
            @Value("${guanxian.security.jwt.principal-claim:preferred_username}") String principalClaim) {
        if (principalClaim == null || principalClaim.isBlank()) {
            throw new IllegalStateException("JWT principal claim must not be blank");
        }
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(principalClaim.trim());
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authoritiesFor);
        return converter;
    }

    @Bean
    @ConditionalOnProperty(
            name = "guanxian.security.mode",
            havingValue = "jwt",
            matchIfMissing = true)
    JwtDecoder jwtDecoder(
            @Value("${guanxian.security.jwt.issuer-uri:}") String issuerUri,
            @Value("${guanxian.security.jwt.jwk-set-uri:}") String jwkSetUri,
            Environment environment) {
        URI issuer = validatedEndpoint("issuer", issuerUri, environment.getActiveProfiles());
        URI jwkSet = validatedEndpoint("JWK set", jwkSetUri, environment.getActiveProfiles());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSet.toString()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer.toString()));
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "demo")
    UserDetailsService userDetailsService(Environment environment) {
        validateSecurityMode("demo", environment.getActiveProfiles());
        return new InMemoryUserDetailsManager(
                demoUser("system-admin", "system123", "SYSTEM_ADMIN").build(),
                demoUser("association-admin", "admin123", "ASSOCIATION_ADMIN").build(),
                demoUser("association-operator", "operator123", "ASSOCIATION_OPERATOR").build(),
                demoUser("enterprise-admin", "enterprise123", "ENTERPRISE_ADMIN").build(),
                demoUser("enterprise-member", "member123", "ENTERPRISE_MEMBER").build(),
                demoUser("observer", "observer123", "OBSERVER").build());
    }

    static Collection<GrantedAuthority> authoritiesFor(Jwt jwt) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        addClaimValues(roles, jwt.getClaim("roles"));
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> realmMap) {
            addClaimValues(roles, realmMap.get("roles"));
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String role : roles) {
            String normalized = normalizeRole(role);
            if (!KNOWN_ROLES.contains(normalized)) {
                continue;
            }
            names.add("ROLE_" + normalized);
            names.addAll(ROLE_PERMISSIONS.getOrDefault(normalized, Set.of()));
        }

        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        addClaimValues(permissions, jwt.getClaim("permissions"));
        permissions.stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(KNOWN_PERMISSIONS::contains)
                .forEach(names::add);

        return names.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    static void validateSecurityMode(String mode, String[] activeProfiles) {
        if (!"jwt".equals(mode) && !"demo".equals(mode)) {
            throw new IllegalStateException("guanxian.security.mode must be jwt or demo");
        }
        if ("demo".equals(mode) && isProduction(activeProfiles)) {
            throw new IllegalStateException(
                    "demo authentication is forbidden in prod/production; set GUANXIAN_SECURITY_MODE=jwt");
        }
    }

    private static URI validatedEndpoint(String label, String value, String[] activeProfiles) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("JWT " + label + " URI must be configured");
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                throw new IllegalStateException("JWT " + label + " URI must be an HTTP(S) endpoint");
            }
            if (isProduction(activeProfiles) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalStateException("JWT " + label + " URI must use HTTPS in production");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("JWT " + label + " URI is invalid", exception);
        }
    }

    private static User.UserBuilder demoUser(String username, String password, String role) {
        List<String> authorities = new ArrayList<>();
        authorities.add("ROLE_" + role);
        authorities.addAll(ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
        return User.withUsername(username)
                .password("{noop}" + password)
                .authorities(authorities.toArray(String[]::new));
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private static void addClaimValues(Set<String> target, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.stream().filter(String.class::isInstance).map(String.class::cast).forEach(target::add);
        } else if (claim instanceof String value) {
            for (String item : value.split("[,\\s]+")) {
                if (!item.isBlank()) {
                    target.add(item);
                }
            }
        }
    }

    private static boolean isProduction(String[] activeProfiles) {
        for (String profile : activeProfiles) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message, null));
    }
}
