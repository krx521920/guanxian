package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import com.guanxian.platform.shared.notification.BusinessNotification;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcosystemWorkflowServiceTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID DEMAND_ENTERPRISE = UUID.randomUUID();
    private static final UUID SUPPLIER_ENTERPRISE = UUID.randomUUID();

    @Test
    void invitationNegotiationFeedbackAndOutcomeAreDurableWorkflowSteps() {
        ActorScope owner = enterprise(DEMAND_ENTERPRISE);
        ActorScope supplier = enterprise(SUPPLIER_ENTERPRISE);
        ActorScope reviewer = reviewer();
        Set<UUID> operational = ConcurrentHashMap.newKeySet();
        operational.addAll(Set.of(DEMAND_ENTERPRISE, SUPPLIER_ENTERPRISE));
        EnterpriseLifecycle lifecycle = operational::contains;
        InMemoryEcosystemCatalogStore catalogStore = new InMemoryEcosystemCatalogStore();
        DemandView demand = catalogStore.createDemand(
                DEMAND_ENTERPRISE,
                new DemandUpsertRequest(
                        "阀门采购", "零泄漏阀门", List.of("燃气管网"), List.of("阀门"),
                        "MEMBERS", null, null, Instant.now().plusSeconds(86400)),
                owner);

        InMemoryEcosystemMatchStore matchStore = new InMemoryEcosystemMatchStore(lifecycle);
        PersistedMatchView match = matchStore.upsert(
                demand,
                List.of(new MatchCandidateDraft(
                        SUPPLIER_ENTERPRISE, "供应企业", "零泄漏球阀", 92, List.of("能力匹配"))),
                owner).getFirst();
        InMemoryEcosystemWorkflowStore workflowStore = new InMemoryEcosystemWorkflowStore(lifecycle);
        List<BusinessNotification> notifications = new ArrayList<>();
        EcosystemWorkflowService service = new EcosystemWorkflowService(
                matchStore, workflowStore, catalogStore, lifecycle,
                PartnerFieldAuthorization.allowAll(),
                (event, actor) -> { notifications.add(event); return 1; });

        assertThrows(PreconditionFailedException.class, () -> service.invite(
                match.id(), match.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "不能提前邀请",
                        Instant.now().plusSeconds(3600)),
                owner));

        PersistedMatchView recommended = matchStore.recommend(
                match.id(), match.version(), reviewer).orElseThrow();
        PersistedMatchView demandConfirmed = matchStore.confirm(
                match.id(), recommended.version(), DEMAND_ENTERPRISE, owner).orElseThrow();
        PersistedMatchView confirmed = matchStore.confirm(
                match.id(), demandConfirmed.version(), SUPPLIER_ENTERPRISE, supplier).orElseThrow();
        assertEquals("CONFIRMED", confirmed.state());

        MatchInvitationView invitation = service.invite(
                match.id(), confirmed.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "请确认合作意向",
                        Instant.now().plusSeconds(3600)),
                owner);
        assertNull(invitation.sentBySubject());
        assertEquals("MATCH_INVITATION", notifications.getLast().notificationType());
        assertEquals(List.of(SUPPLIER_ENTERPRISE), notifications.getLast().enterpriseIds());
        MatchInvitationView accepted = service.respond(
                invitation.id(), invitation.version(),
                new MatchInvitationResponse(true, "同意进入洽谈"), supplier);
        assertEquals("ACCEPTED", accepted.status());
        assertNull(accepted.sentBySubject());
        assertNull(accepted.respondedBySubject());
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
        assertNull(negotiation.recordedBySubject());
        assertEquals("MATCH_NEGOTIATION", notifications.getLast().notificationType());
        assertTrue(notifications.getLast().enterpriseIds().containsAll(
                List.of(DEMAND_ENTERPRISE, SUPPLIER_ENTERPRISE)));

        PersistedMatchView afterInitialContact = matchStore.find(match.id(), reviewer).orElseThrow();
        NegotiationView associationFollowUp = service.addNegotiation(
                match.id(), afterInitialContact.version(),
                new NegotiationRequest(
                        "INITIAL_CONTACT", "协会已协调双方会议", "技术交流", null),
                reviewer);
        assertNull(associationFollowUp.enterpriseId());
        assertNull(associationFollowUp.recordedBySubject());
        assertThrows(PreconditionFailedException.class, () -> service.addNegotiation(
                match.id(), matchStore.find(match.id(), reviewer).orElseThrow().version(),
                new NegotiationRequest("TERMINATED", "终".repeat(1001), null, null), reviewer));

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

        assertThrows(PreconditionFailedException.class, () -> service.feedback(
                match.id(), new MatchFeedbackRequest(
                        5, "SUCCESS", "成功不应有关闭原因", "匹配准确"), supplier));

        MatchFeedbackView supplierFeedback = service.feedback(
                match.id(), new MatchFeedbackRequest(5, "SUCCESS", null, "匹配准确"), supplier);
        assertEquals(SUPPLIER_ENTERPRISE, supplierFeedback.enterpriseId());
        assertNull(supplierFeedback.submittedBySubject());
        assertThrows(com.guanxian.platform.shared.error.PreconditionRequiredException.class,
                () -> service.feedback(
                        match.id(), new MatchFeedbackRequest(
                                5, "SUCCESS", null, "重复提交"), supplier));
        MatchFeedbackView revised = service.feedback(
                match.id(), supplierFeedback.version(),
                new MatchFeedbackRequest(5, "SUCCESS", null, "确认匹配准确"), supplier);
        assertEquals(1, revised.version());
        assertThrows(PreconditionFailedException.class, () -> service.feedback(
                match.id(), 0L,
                new MatchFeedbackRequest(5, "SUCCESS", null, "陈旧版本"), supplier));

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
        assertNull(outcome.archivedBySubject());
        PersistedMatchView archived = matchStore.find(match.id(), owner).orElseThrow();
        assertEquals("ARCHIVED", archived.state());

        operational.remove(SUPPLIER_ENTERPRISE);
        assertEquals(1, service.outcomes(match.id(), reviewer).size());
        assertEquals(6, service.negotiations(match.id(), reviewer).size());
        assertEquals(1, workflowStore.outcomes(match.id(), owner).size());
        assertEquals(1, workflowStore.outcomes(match.id(), reviewer).size());
        assertThrows(NotFoundException.class, () -> service.outcomes(match.id(), owner));
        assertThrows(ForbiddenException.class,
                () -> workflowStore.expirePendingInvitations(match.id(), reviewer));
        assertThrows(PreconditionFailedException.class, () -> service.invite(
                match.id(), archived.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ASSOCIATION_RECOMMENDATION", "历史记录不可续写",
                        Instant.now().plusSeconds(3600)),
                reviewer));
    }

    @Test
    void inMemoryUnitOfWorkRollsBackMatchAndWorkflowMapsWhenInvitationCreationFails() {
        ActorScope owner = enterprise(DEMAND_ENTERPRISE);
        ActorScope supplier = enterprise(SUPPLIER_ENTERPRISE);
        ActorScope reviewer = reviewer();
        InMemoryEcosystemCatalogStore catalogStore = new InMemoryEcosystemCatalogStore();
        DemandView demand = catalogStore.createDemand(
                DEMAND_ENTERPRISE,
                new DemandUpsertRequest(
                        "回滚需求", "说明", List.of(), List.of(), "MEMBERS",
                        null, null, null), owner);
        InMemoryEcosystemMatchStore matchStore = new InMemoryEcosystemMatchStore();
        PersistedMatchView pending = matchStore.upsert(
                demand, List.of(new MatchCandidateDraft(
                        SUPPLIER_ENTERPRISE, "供应企业", "方案", 90, List.of())), owner).getFirst();
        PersistedMatchView recommended = matchStore.recommend(
                pending.id(), pending.version(), reviewer).orElseThrow();
        PersistedMatchView demandConfirmed = matchStore.confirm(
                pending.id(), recommended.version(), DEMAND_ENTERPRISE, owner).orElseThrow();
        PersistedMatchView confirmed = matchStore.confirm(
                pending.id(), demandConfirmed.version(), SUPPLIER_ENTERPRISE, supplier).orElseThrow();
        InMemoryEcosystemWorkflowStore failingWorkflow = new InMemoryEcosystemWorkflowStore() {
            @Override
            public MatchInvitationView createInvitation(
                    UUID matchId, UUID associationId, UUID senderEnterpriseId,
                    MatchInvitationRequest request, ActorScope actor) {
                super.createInvitation(matchId, associationId, senderEnterpriseId, request, actor);
                throw new IllegalStateException("simulated in-memory write failure");
            }
        };
        EcosystemWorkflowService service = new EcosystemWorkflowService(
                matchStore, failingWorkflow, catalogStore);

        assertThrows(IllegalStateException.class, () -> service.invite(
                confirmed.id(), confirmed.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "邀请",
                        Instant.now().plusSeconds(3600)), owner));

        PersistedMatchView restored = matchStore.find(confirmed.id(), owner).orElseThrow();
        assertEquals(MatchLifecycle.CONFIRMED, restored.state());
        assertEquals(confirmed.version(), restored.version());
        assertTrue(failingWorkflow.invitations(confirmed.id(), owner).isEmpty());
    }

    @Test
    void expiredInvitationIsNormalizedAndCanBeReissuedInTheSameRequest() throws Exception {
        ActorScope owner = enterprise(DEMAND_ENTERPRISE);
        ActorScope supplier = enterprise(SUPPLIER_ENTERPRISE);
        ActorScope reviewer = reviewer();
        InMemoryEcosystemCatalogStore catalogStore = new InMemoryEcosystemCatalogStore();
        DemandView demand = catalogStore.createDemand(
                DEMAND_ENTERPRISE,
                new DemandUpsertRequest(
                        "邀请超时需求", "说明", List.of(), List.of(), "MEMBERS",
                        null, null, null), owner);
        InMemoryEcosystemMatchStore matchStore = new InMemoryEcosystemMatchStore();
        PersistedMatchView pending = matchStore.upsert(
                demand, List.of(new MatchCandidateDraft(
                        SUPPLIER_ENTERPRISE, "供应企业", "方案", 90, List.of())), owner).getFirst();
        PersistedMatchView recommended = matchStore.recommend(
                pending.id(), pending.version(), reviewer).orElseThrow();
        PersistedMatchView demandConfirmed = matchStore.confirm(
                pending.id(), recommended.version(), DEMAND_ENTERPRISE, owner).orElseThrow();
        PersistedMatchView confirmed = matchStore.confirm(
                pending.id(), demandConfirmed.version(), SUPPLIER_ENTERPRISE, supplier).orElseThrow();
        InMemoryEcosystemWorkflowStore workflowStore = new InMemoryEcosystemWorkflowStore();
        EcosystemWorkflowService service = new EcosystemWorkflowService(
                matchStore, workflowStore, catalogStore);

        service.invite(
                pending.id(), confirmed.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "首次邀请",
                        Instant.now().plusMillis(100)), owner);
        Thread.sleep(150);
        PersistedMatchView invited = matchStore.find(pending.id(), owner).orElseThrow();
        MatchInvitationView replacement = service.invite(
                pending.id(), invited.version(),
                new MatchInvitationRequest(
                        SUPPLIER_ENTERPRISE, "ENTERPRISE", "重新邀请",
                        Instant.now().plusSeconds(60)), owner);

        assertEquals("PENDING", replacement.status());
        assertEquals(MatchLifecycle.INVITED,
                matchStore.find(pending.id(), owner).orElseThrow().state());
        List<MatchInvitationView> invitations = service.invitations(pending.id(), owner);
        assertEquals(2, invitations.size());
        assertTrue(invitations.stream().anyMatch(value -> "EXPIRED".equals(value.status())));
        assertTrue(invitations.stream().anyMatch(value -> "PENDING".equals(value.status())));
    }

    @Test
    void partnerAndPublicOutcomesRequireMatchAuthorizationAndRedactSensitiveMetadata() {
        UUID matchId = UUID.randomUUID();
        ActorScope external = new ActorScope(
                UUID.randomUUID(), "external-subject", "external", UUID.randomUUID(), null,
                Set.of("ASSOCIATION_ADMIN"), Set.of(ASSOCIATION_ID));
        PersistedMatchView match = new PersistedMatchView(
                matchId, UUID.randomUUID(), DEMAND_ENTERPRISE, SUPPLIER_ENTERPRISE,
                "需求企业", "需求", "场景", "供应企业", "方案", 90, List.of(),
                MatchLifecycle.ARCHIVED, Instant.now(), Instant.now(), Instant.now(),
                null, 9, Instant.now(), Set.of());
        OutcomeArchiveView privateOutcome = outcome(
                matchId, "PRIVATE", "another-subject", new BigDecimal("100.00"));
        OutcomeArchiveView associationOutcome = outcome(
                matchId, "ASSOCIATION", "archive-subject", new BigDecimal("200.00"));
        OutcomeArchiveView partnerOutcome = outcome(
                matchId, "PARTNERS", "archive-subject", new BigDecimal("300.00"));
        OutcomeArchiveView publicOutcome = outcome(
                matchId, "PUBLIC", "archive-subject", new BigDecimal("400.00"));
        EcosystemMatchStore matchStore = mock(EcosystemMatchStore.class);
        EcosystemWorkflowStore workflowStore = mock(EcosystemWorkflowStore.class);
        EcosystemCatalogStore catalogStore = mock(EcosystemCatalogStore.class);
        when(matchStore.find(matchId, external)).thenReturn(Optional.of(match));
        when(workflowStore.outcomes(matchId, external)).thenReturn(List.of(
                privateOutcome, associationOutcome, partnerOutcome, publicOutcome));
        PartnerFieldAuthorization authorized = (actor, enterpriseId, type, resourceId) -> {
            assertEquals("MATCH", type);
            return Optional.of(Set.of("outcomes"));
        };
        EcosystemWorkflowService service = new EcosystemWorkflowService(
                matchStore, workflowStore, catalogStore, enterpriseId -> true, authorized);

        List<OutcomeArchiveView> visible = service.outcomes(matchId, external);

        assertEquals(Set.of("PARTNERS", "PUBLIC"), visible.stream()
                .map(OutcomeArchiveView::visibility).collect(java.util.stream.Collectors.toSet()));
        assertTrue(visible.stream().allMatch(value -> value.contractAmount() == null));
        assertTrue(visible.stream().allMatch(value -> value.archivedBySubject() == null));

        EcosystemWorkflowService denied = new EcosystemWorkflowService(
                matchStore, workflowStore, catalogStore, enterpriseId -> true,
                (actor, enterpriseId, type, resourceId) -> Optional.empty());
        assertThrows(NotFoundException.class, () -> denied.outcomes(matchId, external));
        EcosystemWorkflowService stateOnly = new EcosystemWorkflowService(
                matchStore, workflowStore, catalogStore, enterpriseId -> true,
                (actor, enterpriseId, type, resourceId) -> Optional.of(Set.of("state")));
        assertThrows(NotFoundException.class, () -> stateOnly.outcomes(matchId, external));
    }

    private static OutcomeArchiveView outcome(
            UUID matchId, String visibility, String subject, BigDecimal amount) {
        return new OutcomeArchiveView(
                UUID.randomUUID(), matchId, "成果", "成果说明", amount,
                "CONTRACT", visibility, subject, Instant.now(), 0);
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

    private static ActorScope reviewer() {
        return new ActorScope(
                UUID.randomUUID(), "reviewer", "reviewer",
                ASSOCIATION_ID, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }
}
