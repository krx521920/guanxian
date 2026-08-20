package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.web.MemberUpsertRequest;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberServiceInvariantTest {
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
}
