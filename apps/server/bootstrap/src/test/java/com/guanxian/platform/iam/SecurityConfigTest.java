package com.guanxian.platform.iam;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityConfigTest {
    @Test
    void productionProfilesRejectEnabledDemoCredentials() {
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateDemoUsersConfiguration(true, new String[]{"prod"}));
        assertThrows(IllegalStateException.class,
                () -> SecurityConfig.validateDemoUsersConfiguration(true, new String[]{"PRODUCTION"}));
    }

    @Test
    void developmentOrExplicitDisablePassesTheGuard() {
        assertDoesNotThrow(() -> SecurityConfig.validateDemoUsersConfiguration(true, new String[]{"dev"}));
        assertDoesNotThrow(() -> SecurityConfig.validateDemoUsersConfiguration(false, new String[]{"prod"}));
    }

    @Test
    void userStoreConstructionEnforcesTheProductionGuardAndFailClosedMode() {
        SecurityConfig config = new SecurityConfig();
        Environment production = environmentWithProfiles("prod");
        assertThrows(IllegalStateException.class, () -> config.userDetailsService(true, production));

        InMemoryUserDetailsManager disabled =
                (InMemoryUserDetailsManager) config.userDetailsService(false, production);
        assertFalse(disabled.userExists("system-admin"));

        InMemoryUserDetailsManager development =
                (InMemoryUserDetailsManager) config.userDetailsService(true, environmentWithProfiles("dev"));
        assertTrue(development.userExists("system-admin"));
        assertTrue(development.userExists("enterprise-member"));
    }

    private static Environment environmentWithProfiles(String... profiles) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(profiles);
        return environment;
    }
}
