package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) -> writeError(
                        response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "authentication is required")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED", "authentication is required"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                "ACCESS_DENIED", "permission denied")))
                .build();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${guanxian.security.demo-users-enabled:true}") boolean demoUsersEnabled,
            Environment environment) {
        validateDemoUsersConfiguration(demoUsersEnabled, environment.getActiveProfiles());
        if (!demoUsersEnabled) {
            return new InMemoryUserDetailsManager();
        }
        var systemAdmin = User.withUsername("system-admin")
                .password("{noop}system123")
                .authorities(
                        "ROLE_SYSTEM_ADMIN", "MEMBER_READ", "ENTERPRISE_WRITE",
                        "POLICY_READ", "MATCH_REQUEST", "COLLABORATION_READ",
                        "DASHBOARD_ASSOCIATION_READ", "DASHBOARD_ENTERPRISE_READ")
                .build();
        var associationAdmin = User.withUsername("association-admin")
                .password("{noop}admin123")
                .authorities(
                        "ROLE_ASSOCIATION_ADMIN", "MEMBER_READ", "ENTERPRISE_WRITE",
                        "POLICY_READ", "MATCH_REQUEST", "COLLABORATION_READ", "DASHBOARD_ASSOCIATION_READ")
                .build();
        var associationOperator = User.withUsername("association-operator")
                .password("{noop}operator123")
                .authorities(
                        "ROLE_ASSOCIATION_OPERATOR", "MEMBER_READ", "ENTERPRISE_WRITE",
                        "POLICY_READ", "MATCH_REQUEST", "COLLABORATION_READ", "DASHBOARD_ASSOCIATION_READ")
                .build();
        var enterpriseAdmin = User.withUsername("enterprise-admin")
                .password("{noop}enterprise123")
                .authorities(
                        "ROLE_ENTERPRISE_ADMIN", "MEMBER_READ", "ENTERPRISE_WRITE",
                        "POLICY_READ", "MATCH_REQUEST", "COLLABORATION_READ", "DASHBOARD_ENTERPRISE_READ")
                .build();
        var enterpriseMember = User.withUsername("enterprise-member")
                .password("{noop}member123")
                .authorities(
                        "ROLE_ENTERPRISE_MEMBER", "MEMBER_READ", "POLICY_READ", "MATCH_REQUEST",
                        "COLLABORATION_READ", "DASHBOARD_ENTERPRISE_READ")
                .build();
        var observer = User.withUsername("observer")
                .password("{noop}observer123")
                .authorities("ROLE_OBSERVER", "MEMBER_READ", "POLICY_READ")
                .build();
        return new InMemoryUserDetailsManager(
                systemAdmin, associationAdmin, associationOperator, enterpriseAdmin, enterpriseMember, observer);
    }

    static void validateDemoUsersConfiguration(boolean demoUsersEnabled, String[] activeProfiles) {
        boolean production = Arrays.stream(activeProfiles)
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
        if (demoUsersEnabled && production) {
            throw new IllegalStateException(
                    "demo users must be disabled in the prod/production profile; "
                            + "set GUANXIAN_DEMO_USERS_ENABLED=false");
        }
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
