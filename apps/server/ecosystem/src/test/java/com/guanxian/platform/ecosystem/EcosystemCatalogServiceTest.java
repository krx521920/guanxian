package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcosystemCatalogServiceTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID ENTERPRISE_A = UUID.randomUUID();
    private static final UUID ENTERPRISE_B = UUID.randomUUID();

    private final EcosystemCatalogService service =
            new EcosystemCatalogService(new InMemoryEcosystemCatalogStore());

    @Test
    void offeringLifecycleEnforcesEnterpriseOwnershipVersionAndReview() {
        ActorScope owner = enterpriseAdmin(ENTERPRISE_A);
        OfferingView created = service.createOffering(new OfferingUpsertRequest(
                "零泄漏球阀", "PRODUCT", "适用于高压燃气管线",
                List.of("燃气管网"), List.of("防爆认证"), "MEMBERS"), owner);

        assertEquals("DRAFT", created.status());
        assertEquals(0, created.version());
        assertThrows(ForbiddenException.class, () -> service.updateOffering(
                created.id(), 0, new OfferingUpsertRequest(
                        "越权修改", "PRODUCT", null, List.of(), List.of(), "PRIVATE"),
                enterpriseAdmin(ENTERPRISE_B)));

        OfferingView submitted = service.submitOffering(created.id(), 0, owner);
        assertEquals("PENDING_REVIEW", submitted.status());
        assertThrows(PreconditionFailedException.class,
                () -> service.submitOffering(created.id(), 0, owner));

        OfferingView approved = service.reviewOffering(
                created.id(), 1, new ReviewDecisionRequest(true, "资料完整"), associationReviewer());
        assertEquals("ACTIVE", approved.status());
        assertEquals(2, approved.version());
        assertThrows(PreconditionFailedException.class, () -> service.updateOffering(
                created.id(), 2, new OfferingUpsertRequest(
                        "上线后直接修改", "PRODUCT", null, List.of(), List.of(), "MEMBERS"), owner));

        OfferingView deleted = service.deleteOffering(created.id(), 2, owner);
        OfferingView restored = service.restoreOffering(created.id(), deleted.version(), owner);
        assertEquals("DRAFT", restored.status());
        assertEquals(4, restored.version());
    }

    @Test
    void demandLifecycleValidatesBudgetAndCloseReason() {
        ActorScope owner = enterpriseAdmin(ENTERPRISE_A);
        DemandUpsertRequest invalid = new DemandUpsertRequest(
                "综合探测", "管线探测需求", List.of("城市更新"), List.of("三维建模"),
                "MEMBERS", new BigDecimal("200"), new BigDecimal("100"), Instant.now().plusSeconds(3600));
        assertThrows(PreconditionFailedException.class, () -> service.createDemand(invalid, owner));

        DemandView created = service.createDemand(new DemandUpsertRequest(
                "综合探测", "管线探测需求", List.of("城市更新"), List.of("三维建模"),
                "MEMBERS", new BigDecimal("100"), new BigDecimal("200"), Instant.now().plusSeconds(3600)), owner);
        DemandView submitted = service.submitDemand(created.id(), 0, owner);
        DemandView opened = service.reviewDemand(
                created.id(), submitted.version(), new ReviewDecisionRequest(true, null), associationReviewer());
        DemandView closed = service.closeDemand(
                created.id(), opened.version(), new CloseDemandRequest("已完成供应商遴选"), owner);

        assertEquals("CLOSED", closed.status());
        assertEquals("已完成供应商遴选", closed.closeReason());
        assertEquals(3, closed.version());
    }

    @Test
    void paginationIsBoundedAndScopedToEnterpriseOwnerForDrafts() {
        ActorScope ownerA = enterpriseAdmin(ENTERPRISE_A);
        ActorScope ownerB = enterpriseAdmin(ENTERPRISE_B);
        service.createOffering(new OfferingUpsertRequest(
                "A产品", "PRODUCT", null, List.of(), List.of(), "PRIVATE"), ownerA);
        service.createOffering(new OfferingUpsertRequest(
                "B产品", "PRODUCT", null, List.of(), List.of(), "PRIVATE"), ownerB);

        EcosystemPage<OfferingView> page = service.offerings(ownerA, null, false, 0, 500);
        assertEquals(1, page.total());
        assertEquals(100, page.size());
        assertEquals(ENTERPRISE_A, page.items().getFirst().enterpriseId());
    }

    private static ActorScope enterpriseAdmin(UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(),
                "subject-" + enterpriseId,
                "enterprise-admin",
                ASSOCIATION_ID,
                enterpriseId,
                Set.of("ENTERPRISE_ADMIN"),
                Set.of());
    }

    private static ActorScope associationReviewer() {
        return new ActorScope(
                UUID.randomUUID(),
                "association-reviewer",
                "association-admin",
                ASSOCIATION_ID,
                null,
                Set.of("ASSOCIATION_ADMIN"),
                Set.of());
    }
}
