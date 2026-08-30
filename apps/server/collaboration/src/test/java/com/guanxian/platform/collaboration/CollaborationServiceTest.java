package com.guanxian.platform.collaboration;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborationServiceTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID OTHER_ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000107");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-00000000e002");
    private static final UUID UNRELATED_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-00000000e003");

    private final InMemoryCollaborationStore store = new InMemoryCollaborationStore(true);
    private final CollaborationService service = new CollaborationService(store, authentication -> enterprise());

    @Test
    void memoryAdapterStartsEmptyWhenDemoSeedIsDisabled() {
        CollaborationService emptyService = new CollaborationService(
                new InMemoryCollaborationStore(false), authentication -> enterprise());
        assertTrue(emptyService.findAll(enterprise()).isEmpty());
    }

    @Test
    void linkedMatchSurvivesCollaborationLifecycle() {
        UUID matchId = UUID.randomUUID();
        store.registerMatchScope(matchId, ASSOCIATION, ENTERPRISE, OTHER_ENTERPRISE);
        CollaborationView created = service.create(request("匹配转协作", matchId), enterprise());
        CollaborationView submitted = service.submit(created.id(), created.version(), enterprise());

        assertEquals(matchId, created.matchId());
        assertEquals(matchId, submitted.matchId());
    }

    @Test
    void matchLinksFailClosedAndAssociationLevelMatchItemsAreLimitedToParticipants() {
        UUID matchId = UUID.randomUUID();
        store.registerMatchScope(matchId, ASSOCIATION, ENTERPRISE, OTHER_ENTERPRISE);

        CollaborationView linked = service.create(request("仅匹配双方可见", matchId), reviewer());

        assertEquals(linked.id(), service.get(linked.id(), enterprise(), false).id());
        assertEquals(linked.id(), service.get(linked.id(), otherEnterprise(), false).id());
        assertThrows(NotFoundException.class,
                () -> service.get(linked.id(), unrelatedEnterprise(), false));
        assertEquals(0, service.page(unrelatedEnterprise(), "仅匹配双方可见", false, 0, 20).total());

        CollaborationView associationWide = service.create(request("协会普通协作"), reviewer());
        assertEquals(associationWide.id(),
                service.get(associationWide.id(), unrelatedEnterprise(), false).id());

        assertThrows(ForbiddenException.class,
                () -> service.create(request("未知匹配", UUID.randomUUID()), enterprise()));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("非参与企业尝试关联", matchId), unrelatedEnterprise()));
    }

    @Test
    void completesReviewedLifecycleWithVersionHistoryAndTimeline() {
        CollaborationView created = service.create(request("联合检测试验"), enterprise());

        assertEquals("DRAFT", created.stage());
        assertEquals(0, created.version());

        CollaborationView submitted = service.submit(created.id(), 0, enterprise());
        CollaborationView approved = service.review(
                created.id(), 1, new CollaborationReviewRequest(true, "资料完整"), reviewer());
        CollaborationView started = service.advance(
                created.id(), 2, new CollaborationTransitionRequest("IN_PROGRESS", "进入试验阶段"), enterprise());
        CollaborationView completed = service.advance(
                created.id(), 3, new CollaborationTransitionRequest("COMPLETED", "双方验收完成"), enterprise());

        assertEquals("PENDING_REVIEW", submitted.stage());
        assertEquals("OPEN", approved.stage());
        assertEquals("IN_PROGRESS", started.stage());
        assertEquals("COMPLETED", completed.stage());
        assertEquals(100, completed.progress());
        assertEquals(4, completed.version());
        assertEquals(5, service.history(created.id(), 100, enterprise()).size());
        assertEquals(5, service.activities(created.id(), 100, enterprise()).size());
    }

    @Test
    void enforcesEnterpriseScopeAndOptimisticConcurrency() {
        CollaborationView created = service.create(request("本企业事项"), enterprise());

        assertThrows(NotFoundException.class, () ->
                service.get(created.id(), otherEnterprise(), false));
        assertThrows(PreconditionFailedException.class, () ->
                service.update(created.id(), 99, request("错误版本"), enterprise()));
    }

    @Test
    void softDeleteDisableAndRestoreRemainRecoverable() {
        CollaborationView created = service.create(request("可恢复事项"), enterprise());
        CollaborationView disabled = service.disable(created.id(), 0, enterprise());
        CollaborationView enabled = service.restore(created.id(), 1, enterprise());
        CollaborationView deleted = service.delete(created.id(), 2, enterprise());

        assertTrue(disabled.disabled());
        assertFalse(enabled.disabled());
        assertEquals("DRAFT", enabled.stage());
        assertTrue(deleted.deleted());
        assertThrows(NotFoundException.class, () ->
                service.get(created.id(), enterprise(), false));

        CollaborationView restored = service.restore(created.id(), 3, enterprise());
        assertFalse(restored.deleted());
        assertEquals("DRAFT", restored.stage());
        assertEquals(4, restored.version());
    }

    @Test
    void customActivityIsAuditableInHistory() {
        CollaborationView created = service.create(request("沟通事项"), enterprise());
        CollaborationActivityView activity = service.addActivity(
                created.id(), new CollaborationActivityRequest("meeting", "确认下周现场会"), enterprise());

        assertEquals("MEETING", activity.type());
        assertEquals(2, service.history(created.id(), 100, enterprise()).size());
        assertEquals(2, service.activities(created.id(), 100, enterprise()).size());
    }

    @Test
    void legacyListAndPageUseTheSameScopedStore() {
        CollaborationView created = service.create(request("唯一检索词"), enterprise());

        assertTrue(service.findAll(enterprise()).stream()
                .anyMatch(item -> item.id().equals(created.id())));
        CollaborationPage<CollaborationView> page =
                service.page(enterprise(), "唯一检索词", false, 0, 20);
        assertEquals(1, page.total());
        assertEquals(created.id(), page.items().getFirst().id());
    }

    @Test
    void maximumPageNumberDoesNotOverflowTheStoreOffset() {
        CollaborationPage<CollaborationView> page =
                service.page(enterprise(), null, false, Integer.MAX_VALUE, 100);

        assertTrue(page.items().isEmpty());
        assertEquals(Integer.MAX_VALUE, page.page());
    }

    @Test
    void systemAdministratorReadsGloballyButCannotWriteWithoutAssociationContext() {
        CollaborationView created = service.create(request("协会内事项"), reviewer());

        assertTrue(service.findAll(system(null, null)).stream()
                .anyMatch(item -> item.id().equals(created.id())));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("无上下文写入"), system(null, null)));
        assertThrows(ForbiddenException.class,
                () -> service.disable(created.id(), created.version(), system(null, null)));
    }

    @Test
    void selectedSystemContextNarrowsReadsAndWritesToAssociationAndEnterprise() {
        CollaborationView enterpriseItem = service.create(request("企业一事项"), enterprise());
        CollaborationView otherEnterpriseItem = service.create(request("企业二事项"), otherEnterprise());
        CollaborationView otherAssociationItem = service.create(
                request("其他协会事项"), associationReviewer(OTHER_ASSOCIATION));

        ActorScope associationContext = system(ASSOCIATION, null);
        assertEquals(2, service.page(associationContext, "事项", false, 0, 20).total());
        assertThrows(NotFoundException.class,
                () -> service.get(otherAssociationItem.id(), associationContext, false));

        ActorScope enterpriseContext = system(ASSOCIATION, ENTERPRISE);
        assertEquals(enterpriseItem.id(), service.get(enterpriseItem.id(), enterpriseContext, false).id());
        assertThrows(NotFoundException.class,
                () -> service.get(otherEnterpriseItem.id(), enterpriseContext, false));
        CollaborationView updated = service.update(
                enterpriseItem.id(), enterpriseItem.version(), request("企业一已更新"), enterpriseContext);
        assertEquals("企业一已更新", updated.title());
    }

    @Test
    void inactiveEnterpriseCollaborationIsHistoricalForAdministratorsButNotParticipants() {
        AtomicBoolean active = new AtomicBoolean(true);
        EnterpriseLifecycle lifecycle = ignored -> active.get();
        InMemoryCollaborationStore guardedStore = new InMemoryCollaborationStore(false, lifecycle);
        CollaborationService guarded = new CollaborationService(
                guardedStore, authentication -> enterprise(), lifecycle);

        CollaborationView created = guarded.create(request("冻结后保留的协作"), enterprise());
        active.set(false);

        assertEquals(0, guarded.page(enterprise(), null, true, 0, 20).total());
        assertThrows(NotFoundException.class, () -> guarded.get(created.id(), enterprise(), true));
        assertEquals(created.id(), guarded.get(created.id(), reviewer(), false).id());
        assertEquals(created.id(), guarded.get(
                created.id(), system(ASSOCIATION, ENTERPRISE), false).id());
        assertEquals(1, guarded.page(reviewer(), null, false, 0, 20).total());
        assertThrows(PreconditionFailedException.class, () -> guarded.update(
                created.id(), created.version(), request("冻结期间不可修改"), reviewer()));
        assertThrows(PreconditionFailedException.class,
                () -> guarded.create(request("不可新建协作"), enterprise()));

        active.set(true);
        assertEquals(created.id(), guarded.get(created.id(), enterprise(), false).id());
    }

    private static CollaborationUpsertRequest request(String title) {
        return request(title, null);
    }

    private static CollaborationUpsertRequest request(String title, UUID matchId) {
        return new CollaborationUpsertRequest(
                title, List.of("甲方", "乙方"), "负责人", "HIGH",
                "确认技术参数", LocalDate.of(2026, 9, 1), 10, matchId);
    }

    private static ActorScope enterprise() {
        return new ActorScope(
                UUID.randomUUID(), "enterprise-subject", "enterprise-admin",
                ASSOCIATION, ENTERPRISE, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope otherEnterprise() {
        return new ActorScope(
                UUID.randomUUID(), "other-subject", "other-admin",
                ASSOCIATION, OTHER_ENTERPRISE, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope unrelatedEnterprise() {
        return new ActorScope(
                UUID.randomUUID(), "unrelated-subject", "unrelated-admin",
                ASSOCIATION, UNRELATED_ENTERPRISE, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope reviewer() {
        return associationReviewer(ASSOCIATION);
    }

    private static ActorScope associationReviewer(UUID associationId) {
        return new ActorScope(
                UUID.randomUUID(), "reviewer-subject", "association-admin",
                associationId, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }

    private static ActorScope system(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "system-subject", "system-admin",
                associationId, enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
