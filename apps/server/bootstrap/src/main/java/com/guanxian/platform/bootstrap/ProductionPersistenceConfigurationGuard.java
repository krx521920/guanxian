package com.guanxian.platform.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Prevents accidental non-durable repositories in a production runtime. */
@Component
final class ProductionPersistenceConfigurationGuard {
    ProductionPersistenceConfigurationGuard(
            @Value("${guanxian.business.repository:postgres}") String businessRepository,
            @Value("${guanxian.member.repository:postgres}") String memberRepository,
            @Value("${guanxian.notification.repository:postgres}") String notificationRepository,
            @Value("${guanxian.business.seed-demo-data:false}") boolean businessSeedDemoData,
            @Value("${guanxian.member.seed-demo-data:false}") boolean memberSeedDemoData,
            Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles()).anyMatch(profile ->
                "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
        if (!production) {
            return;
        }
        requirePostgres("business", businessRepository);
        requirePostgres("member", memberRepository);
        requirePostgres("notification", notificationRepository);
        if (businessSeedDemoData || memberSeedDemoData) {
            throw new IllegalStateException("production demo data seeding must be disabled");
        }
    }

    private static void requirePostgres(String name, String repository) {
        if (!"postgres".equalsIgnoreCase(repository == null ? "" : repository.trim())) {
            throw new IllegalStateException(
                    "production " + name + " repository must use PostgreSQL");
        }
    }
}
