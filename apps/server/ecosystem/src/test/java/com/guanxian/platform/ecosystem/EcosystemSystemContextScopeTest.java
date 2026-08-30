package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcosystemSystemContextScopeTest {
    private static final UUID ASSOCIATION_A = UUID.randomUUID();
    private static final UUID ASSOCIATION_B = UUID.randomUUID();
    private static final UUID ENTERPRISE_A1 = UUID.randomUUID();
    private static final UUID ENTERPRISE_A2 = UUID.randomUUID();
    private static final UUID ENTERPRISE_B1 = UUID.randomUUID();
    private static final UUID ENTERPRISE_B2 = UUID.randomUUID();

    @Test
    void systemContextScopesCatalogMatchesAndWorkflowRecords() {
        InMemoryEcosystemCatalogStore catalog = new InMemoryEcosystemCatalogStore();
        ActorScope ownerA = enterprise(ASSOCIATION_A, ENTERPRISE_A1);
        ActorScope ownerB = enterprise(ASSOCIATION_B, ENTERPRISE_B1);
        DemandView demandA = catalog.createDemand(
                ENTERPRISE_A1, demand("A协会需求"), ownerA);
        DemandView demandB = catalog.createDemand(
                ENTERPRISE_B1, demand("B协会需求"), ownerB);
        catalog.createOffering(
                ENTERPRISE_A2,
                new OfferingUpsertRequest(
                        "A协会供应", "SERVICE", "供应说明", List.of(), List.of(), "MEMBERS"),
                enterprise(ASSOCIATION_A, ENTERPRISE_A2));

        ActorScope globalSystem = system(null, null);
        ActorScope systemA = system(ASSOCIATION_A, null);
        ActorScope systemAOwner = system(ASSOCIATION_A, ENTERPRISE_A1);
        ActorScope systemACandidate = system(ASSOCIATION_A, ENTERPRISE_A2);

        assertEquals(2, catalog.listDemands(globalSystem, null, false, 0, 20).size());
        assertEquals(List.of(demandA), catalog.listDemands(systemA, null, false, 0, 20));
        assertEquals(List.of(demandA), catalog.listDemands(systemAOwner, null, false, 0, 20));
        assertTrue(catalog.listDemands(systemACandidate, null, false, 0, 20).isEmpty());
        ApiException missingContext = assertThrows(ApiException.class, () ->
                catalog.createDemand(ENTERPRISE_A1, demand("无上下文写入"), globalSystem));
        assertEquals("ASSOCIATION_CONTEXT_REQUIRED", missingContext.code());
        assertTrue(catalog.updateDemand(
                demandB.id(), demandB.version(), demand("越权修改"), systemA).isEmpty());

        InMemoryEcosystemMatchStore matches = new InMemoryEcosystemMatchStore(
                enterpriseId -> true, catalog);
        PersistedMatchView matchA = matches.upsert(
                demandA, List.of(candidate(ENTERPRISE_A2, "A供应企业")), ownerA).getFirst();
        PersistedMatchView matchB = matches.upsert(
                demandB, List.of(candidate(ENTERPRISE_B2, "B供应企业")), ownerB).getFirst();

        assertEquals(2, matches.list(globalSystem).size());
        assertEquals(List.of(matchA), matches.list(systemA));
        assertTrue(matches.list(systemACandidate).isEmpty());
        assertTrue(matches.find(matchB.id(), systemA).isEmpty());
        ApiException matchContext = assertThrows(ApiException.class, () ->
                matches.upsert(demandA, List.of(candidate(ENTERPRISE_A2, "A供应企业")), globalSystem));
        assertEquals("ASSOCIATION_CONTEXT_REQUIRED", matchContext.code());
        assertThrows(ForbiddenException.class, () ->
                matches.upsert(demandB, List.of(candidate(ENTERPRISE_B2, "B供应企业")), systemA));
        assertThrows(ForbiddenException.class, () ->
                matches.upsert(demandA, List.of(candidate(ENTERPRISE_A2, "A供应企业")), systemACandidate));
        assertTrue(matches.recommend(matchB.id(), matchB.version(), systemA).isEmpty());
        PersistedMatchView recommendedA = matches.recommend(
                matchA.id(), matchA.version(), systemA).orElseThrow();
        assertEquals(List.of(recommendedA), matches.list(systemACandidate));
        assertTrue(matches.confirm(
                matchA.id(), matchA.version(), ENTERPRISE_A2, ownerA).isEmpty());

        InMemoryEcosystemWorkflowStore workflow = new InMemoryEcosystemWorkflowStore();
        workflow.registerMatchContext(matchA, ASSOCIATION_A);
        workflow.registerMatchContext(matchB, ASSOCIATION_B);
        MatchInvitationView invitationA = workflow.createInvitation(
                matchA.id(), ASSOCIATION_A, ENTERPRISE_A1,
                invitation(ENTERPRISE_A2), ownerA);
        MatchInvitationView invitationB = workflow.createInvitation(
                matchB.id(), ASSOCIATION_B, ENTERPRISE_B1,
                invitation(ENTERPRISE_B2), ownerB);

        assertEquals(1, workflow.invitations(matchA.id(), globalSystem).size());
        assertEquals(1, workflow.invitations(matchB.id(), globalSystem).size());
        assertEquals(List.of(invitationA), workflow.invitations(matchA.id(), systemA));
        assertTrue(workflow.invitations(matchB.id(), systemA).isEmpty());
        assertEquals(List.of(invitationA), workflow.invitations(matchA.id(), systemACandidate));
        assertThrows(ApiException.class, () -> workflow.createInvitation(
                matchA.id(), ASSOCIATION_A, null,
                invitation(ENTERPRISE_A2), globalSystem));
        assertThrows(ForbiddenException.class, () -> workflow.createInvitation(
                matchB.id(), ASSOCIATION_A, null,
                invitation(ENTERPRISE_B2), systemA));
        assertTrue(workflow.respondInvitation(
                invitationA.id(), invitationA.version(), true, null, systemAOwner).isEmpty());
        assertEquals("ACCEPTED", workflow.respondInvitation(
                invitationA.id(), invitationA.version(), true, null, systemACandidate)
                .orElseThrow().status());
        assertEquals("INITIAL_CONTACT", workflow.addNegotiation(
                matchA.id(), ASSOCIATION_A, null,
                new NegotiationRequest("INITIAL_CONTACT", "联系", null, null), systemA).stage());
        assertThrows(ForbiddenException.class, () -> workflow.upsertFeedback(
                matchA.id(), ENTERPRISE_A1, null,
                new MatchFeedbackRequest(5, "SUCCESS", null, "反馈"), systemACandidate));
        assertThrows(ForbiddenException.class, () -> workflow.archive(
                matchB.id(), ASSOCIATION_A,
                new OutcomeArchiveRequest("成果", "说明", null, "COOPERATION", "ASSOCIATION"),
                systemA));
        assertEquals(invitationB.id(), workflow.invitations(matchB.id(), globalSystem).getFirst().id());
    }

    private static DemandUpsertRequest demand(String title) {
        return new DemandUpsertRequest(
                title, "需求说明", List.of("地下管线"), List.of("阀门"),
                "MEMBERS", null, null, null);
    }

    private static MatchCandidateDraft candidate(UUID enterpriseId, String name) {
        return new MatchCandidateDraft(
                enterpriseId, name, "解决方案", 90, List.of("能力匹配"));
    }

    private static MatchInvitationRequest invitation(UUID recipient) {
        return new MatchInvitationRequest(recipient, "ENTERPRISE", "邀请", null);
    }

    private static ActorScope enterprise(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "enterprise-" + enterpriseId, "enterprise",
                associationId, enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope system(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "system", "system",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
