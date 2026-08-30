package com.guanxian.platform.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPersistenceConfigurationGuardTest {
    @Test
    void productionRequiresPostgresForEveryPersistentRepository() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");

        assertThatThrownBy(() -> new ProductionPersistenceConfigurationGuard(
                "postgres", "memory", "postgres", false, false, production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("member repository");
        assertThatThrownBy(() -> new ProductionPersistenceConfigurationGuard(
                "postgres", "postgres", "memory", false, false, production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification repository");
        assertThatCode(() -> new ProductionPersistenceConfigurationGuard(
                "postgres", "postgres", "postgres", false, false, production))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ProductionPersistenceConfigurationGuard(
                "postgres", "postgres", "postgres", true, false, production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo data seeding");
        assertThatThrownBy(() -> new ProductionPersistenceConfigurationGuard(
                "postgres", "postgres", "postgres", false, true, production))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo data seeding");
    }

    @Test
    void developmentMayUseMemoryRepositories() {
        assertThatCode(() -> new ProductionPersistenceConfigurationGuard(
                "memory", "memory", "memory", true, true, new MockEnvironment()))
                .doesNotThrowAnyException();
    }
}
