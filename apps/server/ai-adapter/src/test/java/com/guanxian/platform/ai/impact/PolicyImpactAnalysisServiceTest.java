package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ImpactActor;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyImpactAnalysisServiceTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID POLICY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000107");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void deterministicAnalysisPersistsEvidenceAndSupportsReviewHistory() {
        MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore(true);
        PolicyImpactAnalysisService service = new PolicyImpactAnalysisService(
                store, new DeterministicPolicyImpactAnalyzer());
        ImpactActor associationAdmin = associationAdmin(ASSOCIATION);

        PolicyImpactAnalysisView created = service.create(POLICY, ENTERPRISE, associationAdmin);

        assertEquals("HIGH", created.impactLevel());
        assertEquals("PENDING_REVIEW", created.status());
        assertEquals("DETERMINISTIC_LEXICAL", created.analysisMethod());
        assertFalse(created.evidenceChunkIds().isEmpty());
        assertNull(created.modelExecutionId());
        assertEquals(0, created.version());
        assertEquals(created.id(), service.get(created.id(), enterpriseActor(ENTERPRISE)).id());

        PolicyImpactAnalysisView approved = service.review(
                created.id(), 0, true, "证据出处已核验", associationAdmin);
        assertEquals("APPROVED", approved.status());
        assertEquals(1, approved.version());
        assertEquals(2, service.history(created.id(), associationAdmin, 50).size());
        assertEquals("APPROVE", service.history(created.id(), associationAdmin, 50).getFirst().action());

        assertThrows(PolicyImpactException.class,
                () -> service.review(created.id(), 0, true, null, associationAdmin));
    }

    @Test
    void enterpriseCanReadOnlyItsOwnResultsAndAssociationScopeIsEnforced() {
        MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore(true);
        PolicyImpactAnalysisService service = new PolicyImpactAnalysisService(
                store, new DeterministicPolicyImpactAnalyzer());
        PolicyImpactAnalysisView created = service.create(POLICY, ENTERPRISE, associationAdmin(ASSOCIATION));

        PolicyImpactException hidden = assertThrows(PolicyImpactException.class,
                () -> service.get(created.id(), enterpriseActor(UUID.randomUUID())));
        assertEquals(PolicyImpactException.Reason.NOT_FOUND, hidden.reason());
        assertEquals(1, service.page(enterpriseActor(ENTERPRISE), null, null, null, 0, 20).total());
        assertEquals(0, service.page(enterpriseActor(UUID.randomUUID()), null, null, null, 0, 20).total());

        PolicyImpactException wrongAssociation = assertThrows(PolicyImpactException.class,
                () -> service.reanalyze(created.id(), 0, associationAdmin(UUID.randomUUID())));
        assertEquals(PolicyImpactException.Reason.FORBIDDEN, wrongAssociation.reason());
    }

    @Test
    void maximumPageNumberDoesNotOverflowTheStoreOffset() {
        PolicyImpactAnalysisService service = new PolicyImpactAnalysisService(
                new MemoryPolicyImpactAnalysisStore(true), new DeterministicPolicyImpactAnalyzer());

        PolicyImpactPage page = service.page(
                associationAdmin(ASSOCIATION), null, null, null, Integer.MAX_VALUE, 100);

        assertEquals(0, page.items().size());
        assertEquals(Integer.MAX_VALUE, page.page());
    }

    @Test
    void inactiveEnterprisePolicyImpactIsHistoricalForAdministratorsButNotParticipants() {
        AtomicBoolean active = new AtomicBoolean(true);
        EnterpriseLifecycle lifecycle = ignored -> active.get();
        MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore(true, lifecycle);
        PolicyImpactAnalysisService service = new PolicyImpactAnalysisService(
                store, new DeterministicPolicyImpactAnalyzer(), lifecycle);
        PolicyImpactAnalysisView created = service.create(POLICY, ENTERPRISE, associationAdmin(ASSOCIATION));

        active.set(false);
        PolicyImpactException createDenied = assertThrows(PolicyImpactException.class,
                () -> service.create(POLICY, ENTERPRISE, associationAdmin(ASSOCIATION)));
        assertEquals(PolicyImpactException.Reason.PRECONDITION_FAILED, createDenied.reason());
        assertEquals(created.id(), service.get(created.id(), associationAdmin(ASSOCIATION)).id());
        assertEquals(created.id(), service.get(
                created.id(), systemAdmin(ASSOCIATION, ENTERPRISE)).id());
        assertEquals(1, service.page(
                associationAdmin(ASSOCIATION), null, null, null, 0, 20).total());
        PolicyImpactException hidden = assertThrows(PolicyImpactException.class,
                () -> service.get(created.id(), enterpriseActor(ENTERPRISE)));
        assertEquals(PolicyImpactException.Reason.NOT_FOUND, hidden.reason());
        assertEquals(0, service.page(enterpriseActor(ENTERPRISE), null, null, null, 0, 20).total());
        PolicyImpactException writeDenied = assertThrows(PolicyImpactException.class,
                () -> service.review(created.id(), created.version(), true, null,
                        associationAdmin(ASSOCIATION)));
        assertEquals(PolicyImpactException.Reason.PRECONDITION_FAILED, writeDenied.reason());
        assertEquals(1, service.history(created.id(), associationAdmin(ASSOCIATION), 20).size());

        active.set(true);
        assertEquals(created.id(), service.get(created.id(), associationAdmin(ASSOCIATION)).id());
        assertEquals(1, service.history(created.id(), associationAdmin(ASSOCIATION), 20).size());
    }

    @Test
    void selectedSystemContextConstrainsCreateReanalysisReviewGetAndPagination() {
        MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore(true);
        store.putSource(new PolicyImpactAnalysisStore.AnalysisSource(
                POLICY, "外协会政策", OTHER_ENTERPRISE, "外协会企业", OTHER_ASSOCIATION,
                "燃气 管线 监测", List.of(new PolicyImpactAnalysisStore.SourceChunk(
                        UUID.randomUUID(), "燃气管线企业应建立监测记录。"))));
        PolicyImpactAnalysisService service = new PolicyImpactAnalysisService(
                store, new DeterministicPolicyImpactAnalyzer());
        PolicyImpactAnalysisView own = service.create(POLICY, ENTERPRISE, associationAdmin(ASSOCIATION));
        PolicyImpactAnalysisView foreign = service.create(
                POLICY, OTHER_ENTERPRISE, associationAdmin(OTHER_ASSOCIATION));
        ImpactActor globalSystemAdmin = systemAdmin(null, null);
        ImpactActor associationSystemAdmin = systemAdmin(ASSOCIATION, null);
        ImpactActor enterpriseSystemAdmin = systemAdmin(ASSOCIATION, ENTERPRISE);

        assertEquals(2, service.page(globalSystemAdmin, null, null, null, 0, 20).total());
        assertEquals(1, service.page(associationSystemAdmin, null, null, null, 0, 20).total());
        assertEquals(own.id(), service.page(associationSystemAdmin, null, null, null, 0, 20)
                .items().getFirst().id());
        assertEquals(1, service.page(enterpriseSystemAdmin, null, null, null, 0, 20).total());
        assertEquals(foreign.id(), service.get(foreign.id(), globalSystemAdmin).id());

        PolicyImpactException hidden = assertThrows(PolicyImpactException.class,
                () -> service.get(foreign.id(), associationSystemAdmin));
        assertEquals(PolicyImpactException.Reason.NOT_FOUND, hidden.reason());
        PolicyImpactException missingContext = assertThrows(PolicyImpactException.class,
                () -> service.create(POLICY, ENTERPRISE, globalSystemAdmin));
        assertEquals(PolicyImpactException.Reason.ASSOCIATION_CONTEXT_REQUIRED, missingContext.reason());
        PolicyImpactException crossCreate = assertThrows(PolicyImpactException.class,
                () -> service.create(POLICY, OTHER_ENTERPRISE, associationSystemAdmin));
        assertEquals(PolicyImpactException.Reason.FORBIDDEN, crossCreate.reason());
        PolicyImpactException crossReanalysis = assertThrows(PolicyImpactException.class,
                () -> service.reanalyze(foreign.id(), foreign.version(), associationSystemAdmin));
        assertEquals(PolicyImpactException.Reason.FORBIDDEN, crossReanalysis.reason());
        PolicyImpactException crossReview = assertThrows(PolicyImpactException.class,
                () -> service.review(foreign.id(), foreign.version(), true, null, associationSystemAdmin));
        assertEquals(PolicyImpactException.Reason.FORBIDDEN, crossReview.reason());
    }

    private static ImpactActor associationAdmin(UUID associationId) {
        return new ImpactActor(null, "association-admin", "association-admin", associationId, null,
                false, true, true);
    }

    private static ImpactActor enterpriseActor(UUID enterpriseId) {
        return new ImpactActor(null, "enterprise-member", "enterprise-member", ASSOCIATION, enterpriseId,
                false, false, false);
    }

    private static ImpactActor systemAdmin(UUID associationId, UUID enterpriseId) {
        return new ImpactActor(null, "system-admin", "system-admin", associationId, enterpriseId,
                true, false, false);
    }
}
