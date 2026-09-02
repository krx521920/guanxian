package com.guanxian.platform.collaboration;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ApiException;
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
    void matchLinkRequiresBilateralConfirmationAndAnOpenWorkflowState() {
        UUID earlyMatch = UUID.randomUUID();
        UUID closedMatch = UUID.randomUUID();
        UUID archivedMatch = UUID.randomUUID();
        UUID confirmedMatch = UUID.randomUUID();
        store.registerMatchScope(
                earlyMatch, ASSOCIATION, ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "RECOMMENDED");
        store.registerMatchScope(
                closedMatch, ASSOCIATION, ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CLOSED");
        store.registerMatchScope(
                archivedMatch, ASSOCIATION, ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "ARCHIVED");
        store.registerMatchScope(
                confirmedMatch, ASSOCIATION, ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CONFIRMED");

        assertThrows(ForbiddenException.class,
                () -> service.create(request("过早关联", earlyMatch), enterprise()));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("关闭后关联", closedMatch), enterprise()));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("归档后关联", archivedMatch), enterprise()));
        assertEquals(confirmedMatch,
                service.create(request("确认后关联", confirmedMatch), enterprise()).matchId());
    }

    @Test
    void terminalMatchKeepsExistingCollaborationClosableButRejectsNewOrChangedLinks() {
        UUID archivedMatch = UUID.randomUUID();
        store.registerMatchScope(
                archivedMatch, ASSOCIATION, ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CONFIRMED");
        CollaborationView created = service.create(
                request("归档匹配既有协作", archivedMatch), enterprise());
        store.setMatchState(archivedMatch, "ARCHIVED");

        CollaborationView draftUpdated = service.update(
                created.id(), created.version(),
                request("归档后继续维护既有协作", archivedMatch), enterprise());
        service.addActivity(
                draftUpdated.id(),
                new CollaborationActivityRequest("PROGRESS_NOTE", "补齐归档后的协作记录"),
                otherEnterprise());
        CollaborationView submitted = service.submit(
                draftUpdated.id(), draftUpdated.version(), otherEnterprise());
        CollaborationView opened = service.review(
                submitted.id(), submitted.version(),
                new CollaborationReviewRequest(true, "允许既有协作收尾"), reviewer());
        CollaborationView maintained = service.update(
                opened.id(), opened.version(),
                new CollaborationUpsertRequest(
                        opened.title(), opened.participants(), "收尾负责人", opened.priority(),
                        "完成成果确认", opened.dueDate(), 90, opened.matchId()),
                otherEnterprise());
        CollaborationView started = service.advance(
                maintained.id(), maintained.version(),
                new CollaborationTransitionRequest("IN_PROGRESS", "开始收尾"), otherEnterprise());
        CollaborationView completed = service.advance(
                started.id(), started.version(),
                new CollaborationTransitionRequest("COMPLETED", "收尾完成"), otherEnterprise());
        CollaborationView disabled = service.disable(
                completed.id(), completed.version(), enterprise());
        CollaborationView enabled = service.restore(
                disabled.id(), disabled.version(), enterprise());
        CollaborationView deleted = service.delete(
                enabled.id(), enabled.version(), enterprise());
        CollaborationView restored = service.restore(
                deleted.id(), deleted.version(), enterprise());

        assertEquals("DRAFT", restored.stage());
        assertFalse(restored.deleted());
        assertTrue(service.activities(restored.id(), 100, enterprise()).stream()
                .anyMatch(activity -> "补齐归档后的协作记录".equals(activity.detail())));

        UUID closedMatch = UUID.randomUUID();
        store.registerMatchScope(
                closedMatch, ASSOCIATION, ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CONFIRMED");
        CollaborationView closedExisting = service.create(
                request("关闭匹配既有协作", closedMatch), enterprise());
        store.setMatchState(closedMatch, "CLOSED");
        assertEquals("关闭后补充历史",
                service.addActivity(
                        closedExisting.id(),
                        new CollaborationActivityRequest("PROGRESS_NOTE", "关闭后补充历史"),
                        otherEnterprise()).detail());

        CollaborationView ordinary = service.create(request("待改绑普通协作"), enterprise());
        assertThrows(ForbiddenException.class, () -> service.update(
                ordinary.id(), ordinary.version(),
                request("不得改绑关闭匹配", closedMatch), enterprise()));
    }

    @Test
    void softDeletedMatchAndDemandRemainHistoricalOnlyForOriginalParticipants() {
        UUID matchDeleted = UUID.randomUUID();
        UUID demandDeleted = UUID.randomUUID();
        store.registerMatchScope(
                matchDeleted, ASSOCIATION, OTHER_ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CONFIRMED");
        store.registerMatchScope(
                demandDeleted, ASSOCIATION, OTHER_ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CONFIRMED");
        CollaborationView matchHistory = service.create(
                request("匹配删除后的历史协作", matchDeleted), enterprise());
        CollaborationView demandHistory = service.create(
                request("需求删除后的历史协作", demandDeleted), enterprise());

        store.softDeleteMatch(matchDeleted);
        store.softDeleteDemand(demandDeleted);

        assertEquals(matchHistory.id(), service.get(matchHistory.id(), enterprise(), false).id());
        assertEquals(demandHistory.id(), service.get(demandHistory.id(), crossAssociationEnterprise(), false).id());
        service.addActivity(
                matchHistory.id(),
                new CollaborationActivityRequest("PROGRESS_NOTE", "候选企业补齐历史记录"),
                crossAssociationEnterprise());
        CollaborationView submitted = service.submit(
                matchHistory.id(), matchHistory.version(), crossAssociationEnterprise());
        CollaborationView opened = service.review(
                submitted.id(), submitted.version(),
                new CollaborationReviewRequest(true, "原归属协会确认收尾"), reviewer());
        CollaborationView started = service.advance(
                opened.id(), opened.version(),
                new CollaborationTransitionRequest("IN_PROGRESS", "历史收尾开始"),
                crossAssociationEnterprise());
        assertEquals("COMPLETED", service.advance(
                started.id(), started.version(),
                new CollaborationTransitionRequest("COMPLETED", "历史协作归档"),
                associationReviewer(OTHER_ASSOCIATION)).stage());
        assertEquals("DISABLED", service.disable(
                demandHistory.id(), demandHistory.version(),
                associationReviewer(OTHER_ASSOCIATION)).stage());
        assertThrows(NotFoundException.class,
                () -> service.get(matchHistory.id(), unrelatedEnterprise(), false));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("不可新关联已删除匹配", matchDeleted), enterprise()));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("不可新关联已删除需求", demandDeleted), enterprise()));
    }

    @Test
    void crossAssociationMatchParticipantsShareOnlyTheLinkedCollaboration() {
        UUID matchId = UUID.randomUUID();
        store.registerMatchScope(
                matchId, ASSOCIATION, OTHER_ASSOCIATION,
                ENTERPRISE, OTHER_ENTERPRISE, "CONFIRMED");

        CollaborationView linked = service.create(request("跨协会共同协作", matchId), enterprise());
        ActorScope candidate = crossAssociationEnterprise();

        assertEquals(linked.id(), service.get(linked.id(), candidate, false).id());
        assertEquals(1, service.page(candidate, "跨协会共同协作", false, 0, 20).total());
        CollaborationActivityView activity = service.addActivity(
                linked.id(), new CollaborationActivityRequest("PROGRESS_NOTE", "候选企业确认参与"), candidate);
        assertEquals("候选企业确认参与", activity.detail());
        assertEquals(linked.id(), service.get(
                linked.id(), associationReviewer(OTHER_ASSOCIATION), false).id());
        assertThrows(ForbiddenException.class, () -> service.review(
                linked.id(), linked.version(),
                new CollaborationReviewRequest(true, "非归属协会审核"),
                associationReviewer(OTHER_ASSOCIATION)));

        CollaborationView ordinary = service.create(request("普通协会事项"), reviewer());
        assertThrows(NotFoundException.class,
                () -> service.get(ordinary.id(), candidate, false));
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
    void activeCollaborationAllowsOnlyWorkflowMetadataAndReopensBelowCompletion() {
        CollaborationView created = service.create(request("受控推进事项"), enterprise());
        CollaborationView submitted = service.submit(created.id(), created.version(), enterprise());
        CollaborationView opened = service.review(
                created.id(), submitted.version(),
                new CollaborationReviewRequest(true, "同意推进"), reviewer());
        CollaborationUpsertRequest metadata = new CollaborationUpsertRequest(
                opened.title(), opened.participants(), "新负责人", opened.priority(),
                "安排联合测试", LocalDate.of(2026, 9, 8), 68, opened.matchId());

        CollaborationView maintained = service.update(
                opened.id(), opened.version(), metadata, enterprise());

        assertEquals("OPEN", maintained.stage());
        assertEquals("新负责人", maintained.owner());
        assertEquals("安排联合测试", maintained.nextAction());
        assertEquals(68, maintained.progress());
        assertThrows(PreconditionFailedException.class, () -> service.update(
                maintained.id(), maintained.version(),
                new CollaborationUpsertRequest(
                        "篡改标题", maintained.participants(), maintained.owner(), maintained.priority(),
                        maintained.nextAction(), maintained.dueDate(), maintained.progress(), maintained.matchId()),
                enterprise()));
        assertThrows(PreconditionFailedException.class, () -> service.update(
                maintained.id(), maintained.version(),
                new CollaborationUpsertRequest(
                        maintained.title(), maintained.participants(), maintained.owner(), maintained.priority(),
                        maintained.nextAction(), maintained.dueDate(), 100, maintained.matchId()),
                enterprise()));

        CollaborationView started = service.advance(
                maintained.id(), maintained.version(),
                new CollaborationTransitionRequest("IN_PROGRESS", "开始"), enterprise());
        CollaborationView completed = service.advance(
                started.id(), started.version(),
                new CollaborationTransitionRequest("COMPLETED", "完成"), enterprise());
        CollaborationView reopened = service.advance(
                completed.id(), completed.version(),
                new CollaborationTransitionRequest("OPEN", "重新处理遗留项"), enterprise());
        assertEquals(100, completed.progress());
        assertEquals(99, reopened.progress());
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
    void stageFilterUsesTheSameScopeForItemsAndTotal() {
        CollaborationView active = service.create(request("进行中的分页事项"), enterprise());
        CollaborationView completed = service.create(request("已完成的分页事项"), enterprise());
        CollaborationView submitted = service.submit(completed.id(), completed.version(), enterprise());
        CollaborationView approved = service.review(
                completed.id(), submitted.version(),
                new CollaborationReviewRequest(true, "允许推进"), reviewer());
        CollaborationView started = service.advance(
                completed.id(), approved.version(),
                new CollaborationTransitionRequest("IN_PROGRESS", "启动"), enterprise());
        service.advance(
                completed.id(), started.version(),
                new CollaborationTransitionRequest("COMPLETED", "完成"), enterprise());

        CollaborationPage<CollaborationView> activePage = service.page(
                enterprise(), "分页事项", "ACTIVE", false, 0, 20);
        CollaborationPage<CollaborationView> completedPage = service.page(
                enterprise(), "分页事项", "COMPLETED", false, 0, 20);

        assertEquals(1, activePage.total());
        assertEquals(active.id(), activePage.items().getFirst().id());
        assertEquals(1, completedPage.total());
        assertEquals(completed.id(), completedPage.items().getFirst().id());
        assertThrows(ApiException.class, () -> service.page(
                enterprise(), null, "UNKNOWN", false, 0, 20));
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
        CollaborationView disabled = guarded.disable(
                created.id(), created.version(), reviewer());
        assertEquals("DISABLED", disabled.stage());
        CollaborationView deleted = guarded.delete(
                disabled.id(), disabled.version(), system(ASSOCIATION, ENTERPRISE));
        assertTrue(deleted.deleted());
        assertThrows(PreconditionFailedException.class, () -> guarded.restore(
                deleted.id(), deleted.version(), reviewer()));
        assertThrows(PreconditionFailedException.class,
                () -> guarded.create(request("不可新建协作"), enterprise()));

        active.set(true);
        assertEquals(created.id(), guarded.restore(
                deleted.id(), deleted.version(), reviewer()).id());
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

    private static ActorScope crossAssociationEnterprise() {
        return new ActorScope(
                UUID.randomUUID(), "cross-subject", "cross-admin",
                OTHER_ASSOCIATION, OTHER_ENTERPRISE, Set.of("ENTERPRISE_ADMIN"), Set.of());
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
