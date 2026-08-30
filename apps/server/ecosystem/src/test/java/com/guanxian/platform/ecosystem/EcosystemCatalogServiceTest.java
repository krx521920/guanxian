package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertThrows(NotFoundException.class, () -> service.updateOffering(
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

    @Test
    void extremePageUsesLongOffsetWithoutIntegerOverflow() {
        EcosystemCatalogStore store = mock(EcosystemCatalogStore.class);
        EcosystemCatalogService guarded = new EcosystemCatalogService(store, ignored -> true);
        ActorScope actor = systemAdmin();

        guarded.offerings(actor, null, false, Integer.MAX_VALUE, 100);
        guarded.demands(actor, null, false, Integer.MAX_VALUE, 100);

        long expectedOffset = (long) Integer.MAX_VALUE * 100;
        verify(store).listOfferings(actor, null, false, expectedOffset, 100);
        verify(store).listDemands(actor, null, false, expectedOffset, 100);
    }

    @Test
    void detailIncludeDeletedUsesTheSameAdministratorGateAsLists() {
        ActorScope owner = enterpriseAdmin(ENTERPRISE_A);
        ActorScope otherEnterpriseAdmin = enterpriseAdmin(ENTERPRISE_B);
        ActorScope reviewer = associationReviewer();

        OfferingView offering = service.createOffering(new OfferingUpsertRequest(
                "已下架阀门", "PRODUCT", null, List.of(), List.of(), "MEMBERS"), owner);
        offering = service.submitOffering(offering.id(), offering.version(), owner);
        offering = service.reviewOffering(
                offering.id(), offering.version(), new ReviewDecisionRequest(true, null), reviewer);
        assertEquals(offering.id(), service.offering(offering.id(), otherEnterpriseAdmin, false).id());
        OfferingView deletedOffering = service.deleteOffering(offering.id(), offering.version(), owner);

        DemandView demand = service.createDemand(new DemandUpsertRequest(
                "已撤回采购", "历史需求", List.of("燃气管网"), List.of("阀门"),
                "MEMBERS", null, null, Instant.now().plusSeconds(3600)), owner);
        demand = service.submitDemand(demand.id(), demand.version(), owner);
        demand = service.reviewDemand(
                demand.id(), demand.version(), new ReviewDecisionRequest(true, null), reviewer);
        DemandView deletedDemand = service.deleteDemand(demand.id(), demand.version(), owner);

        assertThrows(NotFoundException.class,
                () -> service.offering(deletedOffering.id(), otherEnterpriseAdmin, true));
        assertThrows(NotFoundException.class,
                () -> service.demand(deletedDemand.id(), otherEnterpriseAdmin, true));
        assertEquals(0, service.offerings(otherEnterpriseAdmin, null, true, 0, 20).total());
        assertEquals(0, service.demands(otherEnterpriseAdmin, null, true, 0, 20).total());
        assertEquals(deletedOffering.id(), service.offering(deletedOffering.id(), owner, true).id());
        assertEquals(deletedDemand.id(), service.demand(deletedDemand.id(), owner, true).id());
        assertEquals(deletedOffering.id(), service.offering(deletedOffering.id(), reviewer, true).id());
        assertEquals(deletedDemand.id(), service.demand(deletedDemand.id(), reviewer, true).id());
    }

    @Test
    void inactiveEnterpriseCatalogIsHistoricalForAdministratorsButNotParticipants() {
        AtomicBoolean active = new AtomicBoolean(true);
        EnterpriseLifecycle lifecycle = ignored -> active.get();
        InMemoryEcosystemCatalogStore store = new InMemoryEcosystemCatalogStore(lifecycle);
        EcosystemCatalogService guarded = new EcosystemCatalogService(store, lifecycle);
        ActorScope owner = enterpriseAdmin(ENTERPRISE_A);
        OfferingUpsertRequest request = new OfferingUpsertRequest(
                "可恢复产品", "PRODUCT", null, List.of(), List.of(), "MEMBERS");

        OfferingView created = guarded.createOffering(request, owner);
        assertEquals(1, guarded.offerings(owner, null, false, 0, 20).total());

        active.set(false);
        assertEquals(0, guarded.offerings(owner, null, true, 0, 20).total());
        assertThrows(NotFoundException.class, () -> guarded.offering(created.id(), owner, true));
        assertEquals(created.id(), guarded.offering(created.id(), associationReviewer(), false).id());
        assertEquals(created.id(), guarded.offering(created.id(), systemAdmin(), false).id());
        assertEquals(1, guarded.offerings(associationReviewer(), null, false, 0, 20).total());
        assertThrows(PreconditionFailedException.class,
                () -> guarded.deleteOffering(created.id(), created.version(), associationReviewer()));
        assertThrows(PreconditionFailedException.class, () -> guarded.createOffering(request, owner));

        active.set(true);
        assertEquals(created.id(), guarded.offering(created.id(), owner, false).id());
    }

    @Test
    void authorizedPartnerFieldsAreRedactedAndMissingAuthorizationHidesResource() {
        InMemoryEcosystemCatalogStore store = new InMemoryEcosystemCatalogStore();
        EcosystemCatalogService creator = new EcosystemCatalogService(store);
        ActorScope owner = enterpriseAdmin(ENTERPRISE_A);
        OfferingView created = creator.createOffering(new OfferingUpsertRequest(
                "受控产品", "PRODUCT", "敏感说明", List.of("矿山"), List.of("认证"), "PARTNERS"), owner);
        PartnerFieldAuthorization namesOnly = (actor, enterpriseId, resourceType, resourceId) ->
                Optional.of(Set.of("name"));
        EcosystemCatalogService redacting = new EcosystemCatalogService(store, ignored -> true, namesOnly);

        assertEquals("敏感说明", redacting.offering(created.id(), owner, false).description());
        ActorScope externalPartner = new ActorScope(
                UUID.randomUUID(), "external-partner", "external-partner", UUID.randomUUID(),
                null, Set.of("ASSOCIATION_ADMIN"), Set.of(ASSOCIATION_ID));
        OfferingView value = redacting.authorizedOffering(created, externalPartner).orElseThrow();
        assertEquals("受控产品", value.name());
        assertNull(value.enterpriseName());
        assertNull(value.description());
        assertTrue(value.scenarios().isEmpty());
        assertTrue(value.qualifications().isEmpty());

        EcosystemCatalogService denying = new EcosystemCatalogService(
                store, ignored -> true, (actor, enterpriseId, resourceType, resourceId) -> Optional.empty());
        assertTrue(denying.authorizedOffering(created, externalPartner).isEmpty());
    }

    @Test
    void reviewerMustBelongToTheResourceEnterpriseAssociationBeforeTransition() {
        EcosystemCatalogStore store = mock(EcosystemCatalogStore.class);
        EcosystemCatalogService guarded = new EcosystemCatalogService(store, ignored -> true);
        ActorScope reviewer = associationReviewer();
        UUID offeringId = UUID.randomUUID();
        UUID demandId = UUID.randomUUID();
        OfferingView offering = new OfferingView(
                offeringId, ENTERPRISE_B, "外部企业", "待审产品", "PRODUCT", null,
                List.of(), List.of(), "MEMBERS", "PENDING_REVIEW", 0, false, Instant.now());
        DemandView demand = new DemandView(
                demandId, ENTERPRISE_B, "外部企业", "待审需求", "需求说明",
                List.of(), List.of(), "MEMBERS", null, null, null,
                "PENDING_REVIEW", null, 0, false, Instant.now());
        when(store.findOffering(offeringId, reviewer, false)).thenReturn(Optional.of(offering));
        when(store.findDemand(demandId, reviewer, false)).thenReturn(Optional.of(demand));
        when(store.enterpriseBelongsToAssociation(ENTERPRISE_B, ASSOCIATION_ID)).thenReturn(false);

        ForbiddenException offeringFailure = assertThrows(
                ForbiddenException.class,
                () -> guarded.reviewOffering(
                        offeringId, 0, new ReviewDecisionRequest(true, null), reviewer));
        ForbiddenException demandFailure = assertThrows(
                ForbiddenException.class,
                () -> guarded.reviewDemand(
                        demandId, 0, new ReviewDecisionRequest(true, null), reviewer));

        assertEquals("ASSOCIATION_SCOPE_VIOLATION", offeringFailure.code());
        assertEquals("ASSOCIATION_SCOPE_VIOLATION", demandFailure.code());
        verify(store, never()).transitionOffering(offeringId, 0, "ACTIVE", reviewer);
        verify(store, never()).transitionDemand(demandId, 0, "OPEN", null, reviewer);
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

    private static ActorScope systemAdmin() {
        return new ActorScope(
                UUID.randomUUID(), "system-admin", "system-admin",
                ASSOCIATION_ID, null, Set.of("SYSTEM_ADMIN"), Set.of());
    }

}
