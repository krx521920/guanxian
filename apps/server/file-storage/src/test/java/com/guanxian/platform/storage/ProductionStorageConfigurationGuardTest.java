package com.guanxian.platform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionStorageConfigurationGuardTest {
    @Test
    void productionRequiresMinio() {
        StorageProperties properties = new StorageProperties();
        properties.setBackend("memory");

        assertThatThrownBy(() -> new ProductionStorageConfigurationGuard(
                properties, productionEnvironment(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend=minio");
    }

    @Test
    void productionRequiresRedisBackedWriteLimiting() {
        StorageProperties properties = new StorageProperties();
        properties.setBackend("minio");

        assertThatThrownBy(() -> new ProductionStorageConfigurationGuard(
                properties, productionEnvironment(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis rate limiting");
    }

    @Test
    void durableProductionConfigurationAndDevelopmentMemoryAreAccepted() {
        StorageProperties durable = new StorageProperties();
        durable.setBackend("minio");
        durable.setScanMode("clamav");
        assertThatCode(() -> new ProductionStorageConfigurationGuard(
                durable, productionEnvironment(), true)).doesNotThrowAnyException();

        StorageProperties development = new StorageProperties();
        development.setBackend("memory");
        assertThatCode(() -> new ProductionStorageConfigurationGuard(
                development, new MockEnvironment().withProperty("spring.profiles.active", "dev"), false))
                .doesNotThrowAnyException();
    }

    @Test
    void productionRequiresMalwareScanning() {
        StorageProperties properties = new StorageProperties();
        properties.setBackend("minio");

        assertThatThrownBy(() -> new ProductionStorageConfigurationGuard(
                properties, productionEnvironment(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scan-mode=clamav");
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }
}
