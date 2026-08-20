package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMemberRepositoryTest {
    @Test
    void storesReplacesAndDeletesMemberSnapshotsById() {
        var repository = new InMemoryMemberRepository();
        UUID id = UUID.randomUUID();
        MemberProfile first = member(id, "初始企业", 0);
        MemberProfile updated = member(id, "更新企业", 1);

        repository.insert(first);
        assertEquals(first, repository.findById(id).orElseThrow());
        assertEquals(List.of(first), repository.findAll());

        assertTrue(repository.update(updated, 0));
        assertEquals(updated, repository.findById(id).orElseThrow());
        assertEquals(1, repository.findAll().size());

        assertTrue(repository.deleteById(id, 1));
        assertTrue(repository.findById(id).isEmpty());
        assertFalse(repository.deleteById(id, 1));
    }

    @Test
    void concurrentCasWithSameExpectedVersionSucceedsExactlyOnce() throws Exception {
        var repository = new InMemoryMemberRepository();
        UUID id = UUID.randomUUID();
        repository.insert(member(id, "初始企业", 0));
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> {
                start.await();
                return repository.update(member(id, "更新一", 1), 0);
            };
            Callable<Boolean> second = () -> {
                start.await();
                return repository.update(member(id, "更新二", 1), 0);
            };
            long successes = executor.invokeAll(List.of(first, second)).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();

            assertEquals(1, successes);
            assertEquals(1, repository.findById(id).orElseThrow().version());
        } finally {
            executor.shutdownNow();
        }
    }

    private static MemberProfile member(UUID id, String name, long version) {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new MemberProfile(
                id, name, "91110000TEST000001", "测试单位", null, null, null, null,
                List.of(), List.of(), List.of(), "ACTIVE", version, now, now);
    }
}
