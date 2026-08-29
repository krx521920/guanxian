package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborationServiceTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-00000000e002");

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
        CollaborationView created = service.create(request("匹配转协作", matchId), enterprise());
        CollaborationView submitted = service.submit(created.id(), created.version(), enterprise());

        assertEquals(matchId, created.matchId());
        assertEquals(matchId, submitted.matchId());
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

    private static ActorScope reviewer() {
        return new ActorScope(
                UUID.randomUUID(), "reviewer-subject", "association-admin",
                ASSOCIATION, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }
}
