package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=demo"
})
@Transactional
class PostgresEcosystemSystemContextIntegrationTest {
    private static final UUID ASSOCIATION_A =
            UUID.fromString("75000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B =
            UUID.fromString("75000000-0000-0000-0000-000000000002");
    private static final UUID ENTERPRISE_A1 =
            UUID.fromString("75000000-0000-0000-0000-000000000101");
    private static final UUID ENTERPRISE_A2 =
            UUID.fromString("75000000-0000-0000-0000-000000000102");
    private static final UUID ENTERPRISE_A3 =
            UUID.fromString("75000000-0000-0000-0000-000000000103");
    private static final UUID ENTERPRISE_B1 =
            UUID.fromString("75000000-0000-0000-0000-000000000201");
    private static final UUID ENTERPRISE_B2 =
            UUID.fromString("75000000-0000-0000-0000-000000000202");
    private static final String FIXTURE_PREFIX = "PG系统上下文范围";

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
    EcosystemCatalogService catalog;

    @Autowired
    EcosystemMatchService matches;

    @Autowired
    EcosystemWorkflowService workflow;

    @Autowired
    EcosystemCatalogStore catalogStore;

    @Autowired
    EcosystemMatchStore matchStore;

    @Autowired
    EcosystemWorkflowStore workflowStore;

    @Test
    void postgresEnforcesSystemContextAcrossCatalogMatchesAndWorkflowAndPersistsTimes() {
        seedScopes();
        ActorScope global = system(null, null);
        ActorScope systemA = system(ASSOCIATION_A, null);
        ActorScope systemB = system(ASSOCIATION_B, null);
        ActorScope systemA1 = system(ASSOCIATION_A, ENTERPRISE_A1);
        ActorScope systemA2 = system(ASSOCIATION_A, ENTERPRISE_A2);
        ActorScope systemA3 = system(ASSOCIATION_A, ENTERPRISE_A3);
        ActorScope systemB1 = system(ASSOCIATION_B, ENTERPRISE_B1);
        ActorScope systemB2 = system(ASSOCIATION_B, ENTERPRISE_B2);

        DemandView demandA = openDemand(FIXTURE_PREFIX + "-A需求", systemA1);
        DemandView demandB = openDemand(FIXTURE_PREFIX + "-B需求", systemB1);
        OfferingView offeringA = activateOffering(FIXTURE_PREFIX + "-A供给", systemA2);
        OfferingView offeringB = activateOffering(FIXTURE_PREFIX + "-B供给", systemB2);

        assertCatalogReadScopes(global, systemA, systemA1, systemA2, systemA3,
                demandA, demandB, offeringA, offeringB);
        assertThrows(ApiException.class,
                () -> catalog.createDemand(demandRequest(FIXTURE_PREFIX + "-无上下文写"), global));
        assertThrows(ForbiddenException.class, () -> catalogStore.createDemand(
                ENTERPRISE_B1, demandRequest(FIXTURE_PREFIX + "-body越界"), systemA1));
        assertThrows(NotFoundException.class, () -> catalog.updateDemand(
                demandB.id(), demandB.version(), demandRequest(FIXTURE_PREFIX + "-path越界"), systemA1));

        PersistedMatchView matchA = matchStore.upsert(
                demandA, List.of(candidate(ENTERPRISE_A2, "A供给企业")), systemA).getFirst();
        PersistedMatchView matchB = matchStore.upsert(
                demandB, List.of(candidate(ENTERPRISE_B2, "B供给企业")), systemB).getFirst();

        assertEquals(Set.of(matchA.id(), matchB.id()), ids(matches.persisted(global)));
        assertEquals(Set.of(matchA.id()), ids(matches.persisted(systemA)));
        assertEquals(Set.of(matchA.id()), ids(matches.persisted(systemA1)));
        assertTrue(matches.persisted(systemA2).isEmpty());
        assertTrue(matches.persisted(systemA3).isEmpty());
        assertThrows(ApiException.class,
                () -> matches.recommend(matchA.id(), matchA.version(), global));
        assertThrows(NotFoundException.class,
                () -> matches.recommend(matchB.id(), matchB.version(), systemA));
        assertThrows(ForbiddenException.class, () -> matchStore.upsert(
                demandB, List.of(candidate(ENTERPRISE_B2, "body不能覆盖协会上下文")), systemA));

        PersistedMatchView confirmedA = confirm(matchA, systemA, systemA1, systemA2);
        PersistedMatchView confirmedB = confirm(matchB, systemB, systemB1, systemB2);
        assertTrue(matchStore.upsert(
                demandA,
                List.of(new MatchCandidateDraft(
                        ENTERPRISE_A2, "不得覆盖", "不得覆盖已推进方案", 1, List.of("不得覆盖"))),
                systemA).isEmpty());
        PersistedMatchView protectedMatch = matches.detail(confirmedA.id(), systemA);
        assertEquals(confirmedA.state(), protectedMatch.state());
        assertEquals(confirmedA.version(), protectedMatch.version());
        assertEquals(confirmedA.solution(), protectedMatch.solution());
        assertEquals(confirmedA.reasons(), protectedMatch.reasons());
        Instant invitationExpiresAt = Instant.now().plus(7, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MICROS);
        MatchInvitationRequest invitationARequest = invitation(ENTERPRISE_A2, invitationExpiresAt);

        assertThrows(ApiException.class, () -> workflow.invite(
                confirmedA.id(), confirmedA.version(), invitationARequest, global));
        assertThrows(NotFoundException.class, () -> workflow.invite(
                confirmedB.id(), confirmedB.version(), invitation(ENTERPRISE_B2, invitationExpiresAt), systemA1));
        assertThrows(PreconditionFailedException.class, () -> workflow.invite(
                confirmedA.id(), confirmedA.version(), invitation(ENTERPRISE_B2, invitationExpiresAt), systemA1));
        assertThrows(ForbiddenException.class, () -> workflowStore.createInvitation(
                confirmedA.id(), ASSOCIATION_B, ENTERPRISE_A1, invitationARequest, systemA1));

        MatchInvitationView invitationA = workflow.invite(
                confirmedA.id(), confirmedA.version(), invitationARequest, systemA1);
        MatchInvitationView invitationB = workflow.invite(
                confirmedB.id(), confirmedB.version(), invitation(ENTERPRISE_B2, invitationExpiresAt), systemB1);

        assertEquals(invitationExpiresAt, invitationA.expiresAt());
        assertEquals(invitationExpiresAt, storedInstant(
                "SELECT expires_at FROM match_invitation WHERE id=?", invitationA.id()));
        assertEquals(List.of(invitationA), workflow.invitations(confirmedA.id(), global));
        assertEquals(List.of(invitationB), workflow.invitations(confirmedB.id(), global));
        assertEquals(List.of(invitationA), workflow.invitations(confirmedA.id(), systemA));
        assertThrows(NotFoundException.class,
                () -> workflow.invitations(confirmedA.id(), systemB));
        assertThrows(NotFoundException.class,
                () -> workflow.invitations(confirmedA.id(), systemA3));

        workflow.respond(invitationA.id(), invitationA.version(),
                new MatchInvitationResponse(true, "接受邀请"), systemA2);
        PersistedMatchView negotiatingA = matches.persisted(systemA1).stream()
                .filter(value -> value.id().equals(confirmedA.id()))
                .findFirst().orElseThrow();
        Instant nextActionAt = Instant.now().plus(14, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MICROS);
        NegotiationView negotiation = workflow.addNegotiation(
                negotiatingA.id(), negotiatingA.version(),
                new NegotiationRequest(
                        "INITIAL_CONTACT", "已完成首次联系", "安排技术交流", nextActionAt),
                systemA1);

        assertEquals(nextActionAt, negotiation.nextActionAt());
        assertEquals(nextActionAt, storedInstant(
                "SELECT next_action_at FROM negotiation_record WHERE id=?", negotiation.id()));
        assertEquals(List.of(negotiation), workflow.negotiations(confirmedA.id(), global));
        assertEquals(List.of(negotiation), workflow.negotiations(confirmedA.id(), systemA1));
        assertThrows(NotFoundException.class,
                () -> workflow.negotiations(confirmedA.id(), systemA3));
    }

    @Test
    void postgresCatalogReviewUpdateRejectsAnotherAssociationAtTheWriteBoundary() {
        seedScopes();
        ActorScope systemB1 = system(ASSOCIATION_B, ENTERPRISE_B1);
        ActorScope reviewerA = associationReviewer(ASSOCIATION_A);
        OfferingView offering = catalog.createOffering(
                offeringRequest(FIXTURE_PREFIX + "-跨协会待审供给"), systemB1);
        offering = catalog.submitOffering(offering.id(), offering.version(), systemB1);
        DemandView demand = catalog.createDemand(
                demandRequest(FIXTURE_PREFIX + "-跨协会待审需求"), systemB1);
        demand = catalog.submitDemand(demand.id(), demand.version(), systemB1);

        assertTrue(catalogStore.transitionOffering(
                offering.id(), offering.version(), "ACTIVE", reviewerA).isEmpty());
        assertTrue(catalogStore.transitionDemand(
                demand.id(), demand.version(), "OPEN", null, reviewerA).isEmpty());
        assertEquals("PENDING_REVIEW", jdbc.queryForObject(
                "SELECT status FROM product_service WHERE id=?", String.class, offering.id()));
        assertEquals("PENDING_REVIEW", jdbc.queryForObject(
                "SELECT status FROM cooperation_demand WHERE id=?", String.class, demand.id()));
    }

    @Test
    void associationOutcomeReadHandlesNullEnterpriseContext() {
        seedScopes();
        ActorScope enterprise = system(ASSOCIATION_A, ENTERPRISE_A1);
        ActorScope reviewer = associationReviewer(ASSOCIATION_A);
        DemandView demand = openDemand(FIXTURE_PREFIX + "-协会成果空列表", enterprise);
        PersistedMatchView match = matchStore.upsert(
                demand, List.of(candidate(ENTERPRISE_A2, "A供给企业")), reviewer).getFirst();

        assertTrue(workflow.outcomes(match.id(), reviewer).isEmpty());
    }

    @Test
    void candidateSystemContextCanActForItsEnterpriseButCannotManageTheDemandOwnerWorkflow() {
        seedScopes();
        ActorScope demandAssociation = system(ASSOCIATION_A, null);
        ActorScope candidateAssociation = system(ASSOCIATION_B, null);
        ActorScope demandEnterprise = system(ASSOCIATION_A, ENTERPRISE_A1);
        ActorScope candidateEnterprise = system(ASSOCIATION_B, ENTERPRISE_B2);
        DemandView demand = openDemand(FIXTURE_PREFIX + "-跨协会候选上下文", demandEnterprise);
        PersistedMatchView pending = matchStore.upsert(
                demand, List.of(candidate(ENTERPRISE_B2, "B候选企业")), demandAssociation).getFirst();

        assertThrows(NotFoundException.class, () -> matches.recommend(
                pending.id(), pending.version(), candidateAssociation));
        PersistedMatchView recommended = matches.recommend(
                pending.id(), pending.version(), demandAssociation);
        PersistedMatchView demandConfirmed = matches.confirm(
                recommended.id(), recommended.version(), demandEnterprise);
        PersistedMatchView confirmed = matches.confirm(
                demandConfirmed.id(), demandConfirmed.version(), candidateEnterprise);

        MatchInvitationRequest request = invitation(
                ENTERPRISE_B2, Instant.now().plus(1, ChronoUnit.DAYS));
        assertThrows(ForbiddenException.class, () -> workflow.invite(
                confirmed.id(), confirmed.version(), request, candidateAssociation));
        assertThrows(ForbiddenException.class, () -> matches.close(
                confirmed.id(), confirmed.version(),
                new MatchCloseRequest("候选协会不得关闭"), candidateAssociation));
        assertThrows(ForbiddenException.class, () -> workflow.archive(
                confirmed.id(), confirmed.version(),
                new OutcomeArchiveRequest(
                        "越权成果", "候选协会不得归档", null,
                        "COOPERATION", "ASSOCIATION"), candidateAssociation));

        MatchInvitationView invitation = workflow.invite(
                confirmed.id(), confirmed.version(), request, demandEnterprise);
        workflow.respond(
                invitation.id(), invitation.version(),
                new MatchInvitationResponse(true, "候选企业接受"), candidateEnterprise);
        PersistedMatchView negotiating = matches.persisted(candidateEnterprise).stream()
                .filter(value -> value.id().equals(confirmed.id()))
                .findFirst().orElseThrow();
        assertThrows(ForbiddenException.class, () -> workflow.addNegotiation(
                negotiating.id(), negotiating.version(),
                new NegotiationRequest(
                        "INITIAL_CONTACT", "候选协会不得代替企业记录洽谈", null, null),
                candidateAssociation));
        NegotiationView record = workflow.addNegotiation(
                negotiating.id(), negotiating.version(),
                new NegotiationRequest(
                        "INITIAL_CONTACT", "候选企业完成首次联系", null, null),
                candidateEnterprise);
        assertEquals(ENTERPRISE_B2, record.enterpriseId());
    }

    private void assertCatalogReadScopes(
            ActorScope global,
            ActorScope systemA,
            ActorScope systemA1,
            ActorScope systemA2,
            ActorScope systemA3,
            DemandView demandA,
            DemandView demandB,
            OfferingView offeringA,
            OfferingView offeringB) {
        assertEquals(Set.of(demandA.id(), demandB.id()), demandIds(global));
        assertEquals(Set.of(demandA.id()), demandIds(systemA));
        assertEquals(Set.of(demandA.id()), demandIds(systemA1));
        assertTrue(demandIds(systemA2).isEmpty());
        assertTrue(demandIds(systemA3).isEmpty());

        assertEquals(Set.of(offeringA.id(), offeringB.id()), offeringIds(global));
        assertEquals(Set.of(offeringA.id()), offeringIds(systemA));
        assertTrue(offeringIds(systemA1).isEmpty());
        assertEquals(Set.of(offeringA.id()), offeringIds(systemA2));
        assertTrue(offeringIds(systemA3).isEmpty());
    }

    private Set<UUID> demandIds(ActorScope actor) {
        return catalog.demands(actor, FIXTURE_PREFIX, false, 0, 20).items().stream()
                .map(DemandView::id).collect(java.util.stream.Collectors.toSet());
    }

    private Set<UUID> offeringIds(ActorScope actor) {
        return catalog.offerings(actor, FIXTURE_PREFIX, false, 0, 20).items().stream()
                .map(OfferingView::id).collect(java.util.stream.Collectors.toSet());
    }

    private DemandView openDemand(String title, ActorScope actor) {
        DemandView created = catalog.createDemand(demandRequest(title), actor);
        DemandView submitted = catalog.submitDemand(created.id(), created.version(), actor);
        return catalog.reviewDemand(
                submitted.id(), submitted.version(), new ReviewDecisionRequest(true, null), actor);
    }

    private OfferingView activateOffering(String name, ActorScope actor) {
        OfferingView created = catalog.createOffering(offeringRequest(name), actor);
        OfferingView submitted = catalog.submitOffering(created.id(), created.version(), actor);
        return catalog.reviewOffering(
                submitted.id(), submitted.version(), new ReviewDecisionRequest(true, null), actor);
    }

    private PersistedMatchView confirm(
            PersistedMatchView initial,
            ActorScope associationActor,
            ActorScope demandEnterpriseActor,
            ActorScope candidateEnterpriseActor) {
        PersistedMatchView recommended = matches.recommend(
                initial.id(), initial.version(), associationActor);
        PersistedMatchView demandConfirmed = matches.confirm(
                recommended.id(), recommended.version(), demandEnterpriseActor);
        return matches.confirm(
                demandConfirmed.id(), demandConfirmed.version(), candidateEnterpriseActor);
    }

    private void seedScopes() {
        jdbc.update("""
                INSERT INTO association (id, name, status) VALUES
                    (?, '生态 PostgreSQL 上下文协会 A', 'ACTIVE'),
                    (?, '生态 PostgreSQL 上下文协会 B', 'ACTIVE')
                """, ASSOCIATION_A, ASSOCIATION_B);
        insertEnterprise(ENTERPRISE_A1, ASSOCIATION_A, "生态范围企业 A1");
        insertEnterprise(ENTERPRISE_A2, ASSOCIATION_A, "生态范围企业 A2");
        insertEnterprise(ENTERPRISE_A3, ASSOCIATION_A, "生态范围企业 A3");
        insertEnterprise(ENTERPRISE_B1, ASSOCIATION_B, "生态范围企业 B1");
        insertEnterprise(ENTERPRISE_B2, ASSOCIATION_B, "生态范围企业 B2");
    }

    private void insertEnterprise(UUID id, UUID associationId, String name) {
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, ?, '测试企业', 'ACTIVE')
                """, id, associationId, name);
    }

    private Instant storedInstant(String sql, UUID id) {
        return jdbc.queryForObject(sql,
                (resultSet, row) -> resultSet.getTimestamp(1).toInstant(), id);
    }

    private static DemandUpsertRequest demandRequest(String title) {
        return new DemandUpsertRequest(
                title, "地下管线协作需求", List.of("地下管线"), List.of("阀门"),
                "MEMBERS", null, null, null);
    }

    private static OfferingUpsertRequest offeringRequest(String name) {
        return new OfferingUpsertRequest(
                name, "PRODUCT", "地下管线阀门产品", List.of("地下管线"),
                List.of("测试资质"), "MEMBERS");
    }

    private static MatchCandidateDraft candidate(UUID enterpriseId, String name) {
        return new MatchCandidateDraft(
                enterpriseId, name, "地下管线阀门解决方案", 90, List.of("能力匹配"));
    }

    private static MatchInvitationRequest invitation(UUID recipient, Instant expiresAt) {
        return new MatchInvitationRequest(recipient, "ENTERPRISE", "邀请开展合作", expiresAt);
    }

    private static Set<UUID> ids(List<PersistedMatchView> values) {
        return values.stream().map(PersistedMatchView::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static ActorScope system(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                null, "postgres-ecosystem-system", "system-admin",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }

    private static ActorScope associationReviewer(UUID associationId) {
        return new ActorScope(
                UUID.randomUUID(), "postgres-ecosystem-reviewer", "association-admin",
                associationId, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }
}
