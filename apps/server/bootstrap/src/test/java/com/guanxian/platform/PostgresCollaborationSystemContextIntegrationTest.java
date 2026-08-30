package com.guanxian.platform;

import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.collaboration.CollaborationUpsertRequest;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=demo"
})
class PostgresCollaborationSystemContextIntegrationTest {
    private static final UUID ASSOCIATION_A =
            UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID ASSOCIATION_B =
            UUID.fromString("64000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE_A =
            UUID.fromString("64000000-0000-0000-0000-000000000101");
    private static final UUID ENTERPRISE_B =
            UUID.fromString("64000000-0000-0000-0000-000000000102");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CollaborationService service;

    @Test
    void systemAdministratorContextNarrowsPostgresReadsAndRequiresScopeForWrites() {
        jdbc.update("""
                INSERT INTO association (id, name, status)
                VALUES (?, '协作上下文测试协会B', 'ACTIVE')
                """, ASSOCIATION_B);
        insertEnterprise(ENTERPRISE_A, ASSOCIATION_A, "协作上下文测试企业A");
        insertEnterprise(ENTERPRISE_B, ASSOCIATION_B, "协作上下文测试企业B");

        var itemA = service.create(request("系统范围回归-A"), system(ASSOCIATION_A, ENTERPRISE_A));
        var itemB = service.create(request("系统范围回归-B"), system(ASSOCIATION_B, ENTERPRISE_B));
        var associationItem = service.create(
                request("系统范围回归-协会级"), system(ASSOCIATION_A, null));

        assertEquals(3, service.page(system(null, null), "系统范围回归", false, 0, 20).total());
        assertEquals(2, service.page(system(ASSOCIATION_A, null), "系统范围回归", false, 0, 20).total());
        assertEquals(1, service.page(system(ASSOCIATION_A, ENTERPRISE_A), "系统范围回归", false, 0, 20).total());
        assertEquals(ENTERPRISE_A, itemA.enterpriseId());
        assertNull(associationItem.enterpriseId());

        assertThrows(NotFoundException.class,
                () -> service.get(itemB.id(), system(ASSOCIATION_A, null), false));
        assertThrows(NotFoundException.class,
                () -> service.get(associationItem.id(), system(ASSOCIATION_A, ENTERPRISE_A), false));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("系统范围回归-无上下文写"), system(null, null)));
        assertThrows(ForbiddenException.class,
                () -> service.disable(itemA.id(), itemA.version(), system(null, null)));
    }

    private void insertEnterprise(UUID id, UUID associationId, String name) {
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, ?, '测试企业', 'ACTIVE')
                """, id, associationId, name);
    }

    private static CollaborationUpsertRequest request(String title) {
        return new CollaborationUpsertRequest(
                title, List.of("甲方", "乙方"), "负责人", "HIGH",
                "确认范围", LocalDate.of(2026, 9, 1), 10, null);
    }

    private static ActorScope system(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                null, "system-context-test", "system-admin",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
