package com.guanxian.platform.ecosystem;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Prevents the non-transactional demo repository mode from entering production. */
@Component
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
final class InMemoryEcosystemRepositoryGuard {
    InMemoryEcosystemRepositoryGuard(Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(profile ->
                "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
        if (production) {
            throw new IllegalStateException(
                    "the in-memory ecosystem repository is limited to tests and non-production demos");
        }
    }
}
