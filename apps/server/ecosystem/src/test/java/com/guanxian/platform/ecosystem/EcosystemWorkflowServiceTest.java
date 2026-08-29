package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcosystemWorkflowServiceTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID DEMAND_ENTERPRISE = UUID.randomUUID();
    private static final UUID SUPPLIER_ENTERPRISE = UUID.randomUUID();

    @Test
    void invitationNegotiationFeedbackAndOutcomeAreDurableWorkflowSteps() {
        ActorScope owner = enterprise(DEMAND_ENTERPRISE);
        ActorScope supplier = enterprise(SUPPLIER_ENTERPRISE);
        DemandView demand = new DemandView(
                UUID.randomUUID(), DEMAND_ENTERPRISE, "需求企业", "阀门采购", "零泄漏阀门",
                List.of("燃气管网"), List.of("阀门"), "MEMBERS",
                null, null, Instant.now().plusSeconds(86400), "OPEN", null,
                0, false, Instant.now());

        InMemoryEcosystemMatchStore matchStore = new InMemoryEcosystemMatchStore();
        PersistedMatchView match = matchStore.upsert(
                demand,
                List.of(new MatchCandidateDraft(
                        SUPPLIER_ENTERPRISE, "供应企业", "零泄漏球阀", 92, List.of("能力匹配"))),
                owner).getFirst();
        InMemoryEcosystemCatalogStore catalogStore = new InMemoryEcosystemCatalogStore();
        EcosystemWorkflowService service = new EcosystemWorkflowService(
                matchStore, new InMemoryEcosystemWorkflowStore(), catalogStore);

        assertThrows(PreconditionFailedException.class, () -> service.invite(
                match.id(), match.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "不能提前邀请",
                        Instant.now().plusSeconds(3600)),
                owner));

        PersistedMatchView demandConfirmed = matchStore.confirm(
                match.id(), match.version(), DEMAND_ENTERPRISE, owner).orElseThrow();
        PersistedMatchView confirmed = matchStore.confirm(
                match.id(), demandConfirmed.version(), SUPPLIER_ENTERPRISE, supplier).orElseThrow();
        assertEquals("CONFIRMED", confirmed.state());

        MatchInvitationView invitation = service.invite(
                match.id(), confirmed.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "请确认合作意向",
                        Instant.now().plusSeconds(3600)),
                owner);
        MatchInvitationView accepted = service.respond(
                invitation.id(), invitation.version(),
                new MatchInvitationResponse(true, "同意进入洽谈"), supplier);
        assertEquals("ACCEPTED", accepted.status());
        assertThrows(PreconditionFailedException.class, () -> service.respond(
                invitation.id(), invitation.version(),
                new MatchInvitationResponse(false, "重复响应"), supplier));

        PersistedMatchView negotiating = matchStore.find(match.id(), owner).orElseThrow();
        assertEquals("NEGOTIATING", negotiating.state());
        assertThrows(PreconditionFailedException.class, () -> service.addNegotiation(
                match.id(), negotiating.version(),
                new NegotiationRequest(
                        "TECHNICAL_EXCHANGE", "不能跳过初次联系", "安排现场交流", null),
                supplier));

        NegotiationView negotiation = service.addNegotiation(
                match.id(), negotiating.version(),
                new NegotiationRequest("INITIAL_CONTACT", "双方已建立联系", "技术交流", null),
                supplier);
        assertEquals(1, service.negotiations(match.id(), owner).size());
        assertEquals("INITIAL_CONTACT", negotiation.stage());

        advance(service, matchStore, match.id(), supplier,
                "TECHNICAL_EXCHANGE", "技术参数已核对");
        advance(service, matchStore, match.id(), supplier,
                "COMMERCIAL_NEGOTIATION", "商务条件沟通完成");
        advance(service, matchStore, match.id(), supplier,
                "CONTRACTING", "合同文本进入审核");
        advance(service, matchStore, match.id(), supplier,
                "CONTRACT_SIGNED", "双方合同已签署");
        PersistedMatchView outcomePending = matchStore.find(match.id(), owner).orElseThrow();
        assertEquals("OUTCOME_PENDING", outcomePending.state());

        MatchFeedbackView supplierFeedback = service.feedback(
                match.id(), new MatchFeedbackRequest(5, "SUCCESS", null, "匹配准确"), supplier);
        assertEquals(SUPPLIER_ENTERPRISE, supplierFeedback.enterpriseId());

        assertThrows(PreconditionFailedException.class, () -> service.archive(
                match.id(), outcomePending.version(),
                new OutcomeArchiveRequest(
                        "阀门供应合作", "双方完成技术确认", null, "COOPERATION", "ASSOCIATION"),
                owner));

        service.feedback(
                match.id(), new MatchFeedbackRequest(5, "SUCCESS", null, "达到预期"), owner);

        OutcomeArchiveView outcome = service.archive(
                match.id(), outcomePending.version(),
                new OutcomeArchiveRequest(
                        "阀门供应合作", "双方完成技术确认", null, "COOPERATION", "ASSOCIATION"),
                owner);
        assertEquals(1, service.outcomes(match.id(), supplier).size());
        assertEquals("阀门供应合作", outcome.title());
        assertEquals("ARCHIVED", matchStore.find(match.id(), owner).orElseThrow().state());
    }

    private static void advance(
            EcosystemWorkflowService service,
            InMemoryEcosystemMatchStore matchStore,
            UUID matchId,
            ActorScope actor,
            String stage,
            String summary) {
        PersistedMatchView current = matchStore.find(matchId, actor).orElseThrow();
        service.addNegotiation(
                matchId, current.version(),
                new NegotiationRequest(stage, summary, null, null), actor);
    }

    private static ActorScope enterprise(UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + enterpriseId, "enterprise",
                ASSOCIATION_ID, enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }
}
