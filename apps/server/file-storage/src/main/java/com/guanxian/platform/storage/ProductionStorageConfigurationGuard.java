package com.guanxian.platform.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Refuses non-durable attachment dependencies in a production runtime. */
@Component
final class ProductionStorageConfigurationGuard {
    ProductionStorageConfigurationGuard(
            StorageProperties properties,
            Environment environment,
            @Value("${guanxian.storage.rate-limit.enabled:false}") boolean rateLimitEnabled) {
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(profile ->
                "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
        if (!production) {
            return;
        }
        if (!"minio".equalsIgnoreCase(properties.getBackend())) {
            throw new IllegalStateException(
                    "production attachment storage requires guanxian.storage.backend=minio");
        }
        if (!rateLimitEnabled) {
            throw new IllegalStateException(
                    "production attachment writes require Redis rate limiting");
        }
        if (!"clamav".equalsIgnoreCase(properties.getScanMode())) {
            throw new IllegalStateException(
                    "production attachment writes require guanxian.storage.scan-mode=clamav");
        }
    }
}
