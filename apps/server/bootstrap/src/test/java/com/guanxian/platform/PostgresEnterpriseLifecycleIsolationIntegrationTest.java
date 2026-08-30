package com.guanxian.platform;

import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.collaboration.CollaborationUpsertRequest;
import com.guanxian.platform.ecosystem.DemandUpsertRequest;
import com.guanxian.platform.ecosystem.EcosystemCatalogService;
import com.guanxian.platform.ecosystem.EcosystemMatchService;
import com.guanxian.platform.ecosystem.OfferingUpsertRequest;
import com.guanxian.platform.ecosystem.ReviewDecisionRequest;
import com.guanxian.platform.shared.error.PreconditionFailedException;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=demo"
})
class PostgresEnterpriseLifecycleIsolationIntegrationTest {
    private static final UUID ASSOCIATION =
            UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID DEMAND_ENTERPRISE =
            UUID.fromString("63000000-0000-0000-0000-000000000001");
    private static final UUID SUPPLIER_ENTERPRISE =
            UUID.fromString("63000000-0000-0000-0000-000000000002");

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
    EcosystemCatalogService catalogService;

    @Autowired
    EcosystemMatchService matchService;

    @Autowired
    CollaborationService collaborationService;

    @Test
    void inactiveOrDeletedEnterpriseIsExcludedWithoutDeletingBusinessHistory() {
        insertEnterprise(DEMAND_ENTERPRISE, "生命周期需求企业");
        insertEnterprise(SUPPLIER_ENTERPRISE, "生命周期供应企业");
        ActorScope demandOwner = enterpriseActor(DEMAND_ENTERPRISE);
        ActorScope supplier = enterpriseActor(SUPPLIER_ENTERPRISE);
        ActorScope reviewer = reviewer();

        var demand = catalogService.createDemand(new DemandUpsertRequest(
                "管线阀门采购", "采购零泄漏阀门", List.of("燃气管网"), List.of("阀门"),
                "MEMBERS", null, null, Instant.now().plusSeconds(86_400)), demandOwner);
        demand = catalogService.submitDemand(demand.id(), demand.version(), demandOwner);
        demand = catalogService.reviewDemand(
                demand.id(), demand.version(), new ReviewDecisionRequest(true, null), reviewer);

        var offering = catalogService.createOffering(new OfferingUpsertRequest(
                "零泄漏阀门", "PRODUCT", "适用于燃气管网", List.of("燃气管网"),
                List.of("阀门资质"), "MEMBERS"), supplier);
        offering = catalogService.submitOffering(offering.id(), offering.version(), supplier);
        offering = catalogService.reviewOffering(
                offering.id(), offering.version(), new ReviewDecisionRequest(true, null), reviewer);

        var generatedMatch = matchService.generate(demand.id(), 5, demandOwner).getFirst();
        var match = matchService.recommend(
                generatedMatch.id(), generatedMatch.version(), reviewer);
        var collaboration = collaborationService.create(new CollaborationUpsertRequest(
                "阀门匹配协作", List.of("需求方", "供应方"), "需求负责人", "HIGH",
                "确认技术参数", LocalDate.of(2026, 9, 1), 0, match.id()), demandOwner);

        assertEquals(1, matchService.persisted(demandOwner).size());
        assertEquals(1, collaborationService.page(demandOwner, null, false, 0, 20).total());

        jdbc.update("UPDATE enterprise SET status='DISABLED', updated_at=now() WHERE id=?",
                SUPPLIER_ENTERPRISE);

        assertEquals(0, catalogService.offerings(supplier, null, true, 0, 20).total());
        assertEquals(offering.id(), catalogService.offering(offering.id(), reviewer, false).id());
        assertEquals(0, matchService.persisted(demandOwner).size());
        assertEquals(match.id(), matchService.persisted(reviewer).getFirst().id());
        assertEquals(match.id(), matchService.persisted(demand.id(), reviewer).getFirst().id());
        assertThrows(PreconditionFailedException.class,
                () -> matchService.recommend(match.id(), match.version(), reviewer));
        assertEquals(0, collaborationService.page(demandOwner, null, true, 0, 20).total());
        assertEquals(collaboration.id(), collaborationService.get(
                collaboration.id(), reviewer, false).id());
        assertThrows(PreconditionFailedException.class, () -> collaborationService.update(
                collaboration.id(), collaboration.version(), new CollaborationUpsertRequest(
                        "冻结期间不可修改", List.of("需求方", "供应方"), "需求负责人", "HIGH",
                        "确认技术参数", LocalDate.of(2026, 9, 1), 0, match.id()), reviewer));
        assertThrows(PreconditionFailedException.class, () -> catalogService.createOffering(
                new OfferingUpsertRequest("冻结期间产品", "PRODUCT", null, List.of(), List.of(), "PRIVATE"),
                supplier));

        jdbc.update("UPDATE enterprise SET status='ACTIVE', updated_at=now() WHERE id=?",
                SUPPLIER_ENTERPRISE);
        assertEquals(offering.id(), catalogService.offering(offering.id(), supplier, false).id());
        assertEquals(match.id(), matchService.persisted(demandOwner).getFirst().id());
        assertEquals(collaboration.id(),
                collaborationService.get(collaboration.id(), demandOwner, false).id());

        jdbc.update("""
                UPDATE enterprise
                   SET status='DELETED', deleted_at=now(), deleted_by_subject='test',
                       status_before_delete='ACTIVE', updated_at=now()
                 WHERE id=?
                """, DEMAND_ENTERPRISE);

        assertEquals(0, catalogService.demands(demandOwner, null, true, 0, 20).total());
        assertEquals(demand.id(), catalogService.demand(demand.id(), reviewer, false).id());
        assertEquals(match.id(), matchService.persisted(reviewer).getFirst().id());
        assertThrows(PreconditionFailedException.class,
                () -> matchService.recommend(match.id(), match.version(), reviewer));
        assertEquals(collaboration.id(), collaborationService.get(
                collaboration.id(), reviewer, true).id());
        assertThrows(PreconditionFailedException.class, () -> catalogService.createDemand(
                new DemandUpsertRequest(
                        "删除期间需求", "不可创建", List.of(), List.of(), "PRIVATE",
                        null, null, null), demandOwner));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM product_service WHERE id=?", Integer.class, offering.id()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM cooperation_demand WHERE id=?", Integer.class, demand.id()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM ecosystem_match WHERE id=?", Integer.class, match.id()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM collaboration_task WHERE id=?", Integer.class, collaboration.id()));
    }

    private void insertEnterprise(UUID id, String name) {
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, ?, '测试企业', 'ACTIVE')
                """, id, ASSOCIATION, name);
    }

    private static ActorScope enterpriseActor(UUID enterpriseId) {
        return new ActorScope(
                null, "subject-" + enterpriseId, "enterprise-admin", ASSOCIATION, enterpriseId,
                Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope reviewer() {
        return new ActorScope(
                null, "association-reviewer", "association-reviewer", ASSOCIATION, null,
                Set.of("ASSOCIATION_ADMIN"), Set.of());
    }
}
