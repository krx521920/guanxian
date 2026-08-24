package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ImpactActor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyImpactAnalysisServiceTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID POLICY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void deterministicAnalysisPersistsEvidenceAndSupportsReviewHistory() {
        MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore();
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
        MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore();
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

    private static ImpactActor associationAdmin(UUID associationId) {
        return new ImpactActor(null, "association-admin", "association-admin", associationId, null,
                false, true, true);
    }

    private static ImpactActor enterpriseActor(UUID enterpriseId) {
        return new ImpactActor(null, "enterprise-member", "enterprise-member", ASSOCIATION, enterpriseId,
                false, false, false);
    }
}
