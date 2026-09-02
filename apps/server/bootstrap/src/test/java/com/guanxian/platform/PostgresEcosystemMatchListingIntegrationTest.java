package com.guanxian.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.notification.repository=postgres",
        "guanxian.security.mode=demo"
})
@AutoConfigureMockMvc
class PostgresEcosystemMatchListingIntegrationTest {
    private static final String LOCAL_ASSOCIATION = "00000000-0000-0000-0000-000000000106";
    private static final String FOREIGN_ASSOCIATION = "00000000-0000-0000-0000-000000000206";
    private static final String BOUND_ENTERPRISE = "00000000-0000-0000-0000-000000000201";
    private static final String LOCAL_ENTERPRISE = "00000000-0000-0000-0000-000000000202";
    private static final String FOREIGN_ENTERPRISE = "00000000-0000-0000-0000-000000000301";
    private static final String FOREIGN_SUPPLIER = "00000000-0000-0000-0000-000000000302";

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
    MockMvc mockMvc;

    @BeforeEach
    void insertMatchesAcrossDataScopes() {
        jdbc.update("""
                INSERT INTO association (id, name, status)
                VALUES (?::uuid, '外部测试协会', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, FOREIGN_ASSOCIATION);
        insertEnterprise(BOUND_ENTERPRISE, LOCAL_ASSOCIATION, "本会需求企业");
        insertEnterprise(LOCAL_ENTERPRISE, LOCAL_ASSOCIATION, "本会另一企业");
        insertEnterprise(FOREIGN_ENTERPRISE, FOREIGN_ASSOCIATION, "外会需求企业");
        insertEnterprise(FOREIGN_SUPPLIER, FOREIGN_ASSOCIATION, "外会供应企业");

        insertDemand("10000000-0000-0000-0000-000000000001", BOUND_ENTERPRISE, "本企业需求");
        insertDemand("10000000-0000-0000-0000-000000000002", LOCAL_ENTERPRISE, "本会其他需求");
        insertDemand("10000000-0000-0000-0000-000000000003", FOREIGN_ENTERPRISE, "外会需求");

        insertMatch("20000000-0000-0000-0000-000000000001",
                "10000000-0000-0000-0000-000000000001", LOCAL_ENTERPRISE, "本企业需求");
        insertMatch("20000000-0000-0000-0000-000000000002",
                "10000000-0000-0000-0000-000000000002", LOCAL_ENTERPRISE, "本会其他需求");
        insertMatch("20000000-0000-0000-0000-000000000003",
                "10000000-0000-0000-0000-000000000003", FOREIGN_SUPPLIER, "外会需求");
    }

    @Test
    void listsPersistedMatchesOnlyWithinTheAuthenticatedScope() throws Exception {
        mockMvc.perform(get("/api/v1/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].demandTitle").value("本企业需求"));

        mockMvc.perform(get("/api/v1/matches")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].demandTitle",
                        containsInAnyOrder("本企业需求", "本会其他需求")));

        mockMvc.perform(get("/api/v1/matches")
                        .with(httpBasic("system-admin", "system123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].demandTitle",
                        containsInAnyOrder("本企业需求", "本会其他需求", "外会需求")));
    }

    private void insertEnterprise(String id, String associationId, String name) {
        jdbc.update("""
                INSERT INTO enterprise (
                    id, association_id, name, enterprise_roles, service_scenarios,
                    visibility, status)
                VALUES (?::uuid, ?::uuid, ?, '[]'::jsonb, '[]'::jsonb, 'MEMBERS', 'ACTIVE')
                """, id, associationId, name);
    }

    private void insertDemand(String id, String enterpriseId, String title) {
        jdbc.update("""
                INSERT INTO cooperation_demand (
                    id, enterprise_id, title, description, scenarios,
                    required_capabilities, visibility, status)
                VALUES (?::uuid, ?::uuid, ?, '测试需求', '[]'::jsonb,
                        '[]'::jsonb, 'DIRECTED', 'OPEN')
                """, id, enterpriseId, title);
    }

    private void insertMatch(String id, String demandId, String candidateId, String title) {
        jdbc.update("""
                INSERT INTO ecosystem_match (
                    id, demand_id, candidate_enterprise_id, score, explanation,
                    review_status, demand_title_snapshot, reasons, state)
                VALUES (?::uuid, ?::uuid, ?::uuid, 80, '{}'::jsonb,
                        'PENDING', ?, '[]'::jsonb, 'PENDING_CONFIRMATION')
                """, id, demandId, candidateId, title);
    }
}
