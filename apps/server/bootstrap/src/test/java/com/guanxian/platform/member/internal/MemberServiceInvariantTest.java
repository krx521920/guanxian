package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.member.web.MemberReviewRequest;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import com.guanxian.platform.shared.notification.BusinessNotification;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberServiceInvariantTest {
    @Test
    void ownerCanEditOwnOrdinaryFieldsButCannotChangeIdentityScopeOrApproveTheProfile() {
        MemberService service = new MemberService(new InMemoryMemberRepository());
        var own = service.create(request("既有主体", "OWNER001", "ACTIVE", ASSOCIATION));
        var other = service.create(request("其他主体", "OWNER002", "ACTIVE", ASSOCIATION));
        var owner = new ActorScope(UUID.randomUUID(), "owner", "owner", ASSOCIATION, own.id(), Set.of("ENTERPRISE_ADMIN"), Set.of());
        assertThrows(ForbiddenException.class, () -> service.update(own.id(),0,request("改名主体","OWNER001","ACTIVE",ASSOCIATION),owner));
        assertThrows(ForbiddenException.class, () -> service.update(own.id(),0,request("既有主体","CHANGED","ACTIVE",ASSOCIATION),owner));
        assertThrows(ForbiddenException.class, () -> service.update(own.id(),0,request("既有主体","OWNER001","ACTIVE",OTHER_ASSOCIATION),owner));
        assertThrows(ApiException.class, () -> service.update(other.id(),0,request("其他主体","OWNER002","ACTIVE",ASSOCIATION),owner));
        assertThrows(ForbiddenException.class, () -> service.update(own.id(),0,request("既有主体","OWNER001","ACTIVE",ASSOCIATION),owner));
        assertEquals("ACTIVE",service.get(own.id()).status());
        assertEquals(0,service.get(own.id()).version());
        assertThrows(PreconditionFailedException.class, () -> service.update(own.id(),99,request("既有主体","OWNER001","ACTIVE",ASSOCIATION),owner));
        assertThrows(ForbiddenException.class, () -> service.review(own.id(),0,new MemberReviewRequest("ACTIVE","自己审核"),owner));
    }
    private static final UUID ASSOCIATION = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ASSOCIATION = UUID.fromString("71000000-0000-0000-0000-000000000002");

    @Test
    void memberReviewPublishesARecipientScopedNotification() {
        List<BusinessNotification> notifications = new ArrayList<>();
        MemberService service = new MemberService(
                new InMemoryMemberRepository(), new InMemoryAuditTrail(),
                PartnerFieldAuthorization.allowAll(),
                (event, actor) -> { notifications.add(event); return 1; });
        var pending = service.create(request("待审核通知企业", "NOTICE001", "PENDING_REVIEW"));
        ActorScope reviewer = new ActorScope(
                UUID.randomUUID(), "reviewer", "reviewer", pending.associationId(), null,
                Set.of("ASSOCIATION_ADMIN"), Set.of());

        var reviewed = service.review(
                pending.id(), pending.version(), new MemberReviewRequest("ACTIVE", "资料完整"), reviewer);

        assertEquals(1, notifications.size());
        assertEquals("MEMBER_REVIEWED", notifications.getFirst().notificationType());
        assertEquals(List.of(reviewed.id()), notifications.getFirst().enterpriseIds());
        assertEquals(reviewed.version(), notifications.getFirst().resourceVersion());
    }

    @Test
    void allMutationsShareTheSameIntrinsicLock() throws Exception {
        assertTrue(Modifier.isSynchronized(
                MemberService.class.getMethod("create", MemberUpsertRequest.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(
                MemberService.class.getMethod(
                        "update", UUID.class, long.class, MemberUpsertRequest.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(
                MemberService.class.getMethod("delete", UUID.class, long.class).getModifiers()));
    }

    @Test
    void concurrentCreatesAtomicallyEnforceNormalizedCreditCodeUniqueness() throws Exception {
        MemberService service = new MemberService(new InMemoryMemberRepository());
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> lowerCase = createTask(service, start, "企业一", "  abc123  ");
            Callable<String> upperCase = createTask(service, start, "企业二", "ABC123");
            var results = executor.invokeAll(List.of(lowerCase, upperCase)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertEquals(1, results.stream().filter("CREATED"::equals).count());
            assertEquals(1, results.stream().filter("CONFLICT"::equals).count());
            assertEquals(1, service.findAll(null).size());
            assertEquals("ABC123", service.findAll(null).getFirst().unifiedSocialCreditCode());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void statusIsCanonicalAndBlankRetainsTheActiveDefault() {
        MemberService service = new MemberService(new InMemoryMemberRepository());
        var active = service.create(request("默认状态企业", "STATE001", "  "));
        var pending = service.create(request("待审核企业", "STATE002", " pending_review "));

        assertEquals("ACTIVE", active.status());
        assertEquals("PENDING_REVIEW", pending.status());
    }

    @Test
    void downstreamParticipationRequiresAnActiveNonDeletedMember() {
        MemberService service = new MemberService(new InMemoryMemberRepository());
        var active = service.create(request("生命周期企业", "LIFECYCLE001", "ACTIVE"));

        assertTrue(service.isOperational(active.id()));

        var disabled = service.update(
                active.id(), active.version(), request("生命周期企业", "LIFECYCLE001", "DISABLED"));
        assertFalse(service.isOperational(disabled.id()));

        var reactivated = service.update(
                disabled.id(), disabled.version(), request("生命周期企业", "LIFECYCLE001", "ACTIVE"));
        var deleted = service.delete(reactivated.id(), reactivated.version());
        assertFalse(service.isOperational(deleted.id()));

        var restored = service.restore(deleted.id(), deleted.version(), new com.guanxian.platform.shared.security.ActorScope(
                null, "internal-system", "internal-system", restoredAssociation(deleted), null,
                java.util.Set.of("SYSTEM_ADMIN"), java.util.Set.of()));
        assertTrue(service.isOperational(restored.id()));
    }

    @Test
    void systemAdministratorContextBoundsEveryMemberReadAndMutation() {
        MemberService service = new MemberService(new InMemoryMemberRepository());
        var own = service.create(request("上下文企业", "CTX001", "ACTIVE", ASSOCIATION));
        var sameAssociation = service.create(request("同协会企业", "CTX002", "ACTIVE", ASSOCIATION));
        var foreign = service.create(request("外协会企业", "CTX003", "ACTIVE", OTHER_ASSOCIATION));
        ActorScope global = systemActor(null, null);
        ActorScope selectedAssociation = systemActor(ASSOCIATION, null);
        ActorScope selectedEnterprise = systemActor(ASSOCIATION, own.id());

        assertEquals(3, service.findAll(null, global).size());
        assertEquals(2, service.findAll(null, selectedAssociation).size());
        assertEquals(List.of(own.id()), service.findAll(null, selectedEnterprise).stream()
                .map(com.guanxian.platform.member.api.MemberProfile::id).toList());
        assertThrows(NotFoundException.class, () -> service.get(foreign.id(), selectedAssociation));
        assertThrows(NotFoundException.class, () -> service.get(sameAssociation.id(), selectedEnterprise));

        ApiException missingContext = assertThrows(ApiException.class,
                () -> service.create(request("无上下文写入", "CTX004", "ACTIVE", ASSOCIATION), global));
        assertEquals("ASSOCIATION_CONTEXT_REQUIRED", missingContext.code());
        assertThrows(ForbiddenException.class,
                () -> service.create(request("跨协会写入", "CTX005", "ACTIVE", OTHER_ASSOCIATION),
                        selectedAssociation));
        assertThrows(ForbiddenException.class,
                () -> service.create(request("企业上下文新建", "CTX006", "ACTIVE", ASSOCIATION),
                        selectedEnterprise));
        assertThrows(ForbiddenException.class,
                () -> service.createImported(
                        request("跨协会导入", "CTX007", "ACTIVE", OTHER_ASSOCIATION),
                        OTHER_ASSOCIATION, selectedAssociation));

        assertThrows(ForbiddenException.class,
                () -> service.update(foreign.id(), foreign.version(),
                        request("外协会修改", "CTX003", "ACTIVE", OTHER_ASSOCIATION), selectedAssociation));
        assertThrows(ForbiddenException.class,
                () -> service.review(foreign.id(), foreign.version(),
                        new MemberReviewRequest("ACTIVE", null), selectedAssociation));
        assertThrows(ForbiddenException.class,
                () -> service.delete(foreign.id(), foreign.version(), selectedAssociation));
        assertThrows(ForbiddenException.class,
                () -> service.restore(foreign.id(), foreign.version(), selectedAssociation));
        assertThrows(ForbiddenException.class,
                () -> service.update(sameAssociation.id(), sameAssociation.version(),
                        request("同协会越界修改", "CTX002", "ACTIVE", ASSOCIATION), selectedEnterprise));
    }

    @Test
    void twoUpdatesWithTheSameVersionAllowOnlyOneWrite() throws Exception {
        MemberService service = new MemberService(new InMemoryMemberRepository());
        var original = service.create(request("并发更新企业", "CAS001", "ACTIVE"));
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<String> first = updateTask(service, original.id(), start, "更新一");
            Callable<String> second = updateTask(service, original.id(), start, "更新二");
            var results = executor.invokeAll(List.of(first, second)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertEquals(1, results.stream().filter("UPDATED"::equals).count());
            assertEquals(1, results.stream().filter("PRECONDITION_FAILED"::equals).count());
            assertEquals(1, service.get(original.id()).version());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Callable<String> createTask(
            MemberService service, CyclicBarrier start, String name, String creditCode) {
        return () -> {
            start.await();
            try {
                service.create(request(name, creditCode, " active "));
                return "CREATED";
            } catch (ConflictException exception) {
                return "CONFLICT";
            }
        };
    }

    private static Callable<String> updateTask(
            MemberService service, UUID id, CyclicBarrier start, String name) {
        return () -> {
            start.await();
            try {
                service.update(id, 0, request(name, "CAS001", "ACTIVE"));
                return "UPDATED";
            } catch (PreconditionFailedException exception) {
                return "PRECONDITION_FAILED";
            }
        };
    }

    private static MemberUpsertRequest request(String name, String creditCode, String status) {
        return new MemberUpsertRequest(
                name, creditCode, "测试单位", null, null, null, null,
                null, null, null, status);
    }

    private static MemberUpsertRequest request(
            String name, String creditCode, String status, UUID associationId) {
        return new MemberUpsertRequest(
                name, creditCode, "测试单位", null, null, null, null,
                null, null, null, null, status, associationId);
    }

    private static ActorScope systemActor(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                null, "system-admin", "system-admin", associationId, enterpriseId,
                Set.of("SYSTEM_ADMIN"), Set.of());
    }

    private static UUID restoredAssociation(com.guanxian.platform.member.api.MemberProfile member) {
        return member.associationId();
    }
}
