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

        MatchInvitationView invitation = service.invite(
                match.id(),
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

        NegotiationView negotiation = service.addNegotiation(
                match.id(),
                new NegotiationRequest("TECHNICAL_REVIEW", "技术参数已核对", "安排现场交流", null),
                supplier);
        assertEquals(1, service.negotiations(match.id(), owner).size());
        assertEquals("TECHNICAL_REVIEW", negotiation.stage());

        MatchFeedbackView feedback = service.feedback(
                match.id(), new MatchFeedbackRequest(5, "SUCCESS", null, "匹配准确"), supplier);
        assertEquals(SUPPLIER_ENTERPRISE, feedback.enterpriseId());

        PersistedMatchView confirmed = matchStore.transition(
                match.id(), match.version(), "CONFIRMED", null, supplier).orElseThrow();
        OutcomeArchiveView outcome = service.archive(
                confirmed.id(),
                new OutcomeArchiveRequest(
                        "阀门供应合作", "双方完成技术确认", null, "COOPERATION", "ASSOCIATION"),
                owner);
        assertEquals(1, service.outcomes(match.id(), supplier).size());
        assertEquals("阀门供应合作", outcome.title());
    }

    private static ActorScope enterprise(UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + enterpriseId, "enterprise",
                ASSOCIATION_ID, enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }
}
