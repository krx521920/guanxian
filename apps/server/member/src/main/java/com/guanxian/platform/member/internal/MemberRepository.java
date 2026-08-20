package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port kept inside the member module. Domain rules and single-JVM
 * mutation serialization remain in {@link MemberService}. A future database
 * adapter must implement update and delete as version-conditioned CAS operations;
 * an application-level read followed by an unconditional write is not sufficient
 * when more than one server instance is running.
 */
interface MemberRepository {
    List<MemberProfile> findAll();

    Optional<MemberProfile> findById(UUID id);

    void insert(MemberProfile member);

    boolean update(MemberProfile member, long expectedVersion);

    boolean deleteById(UUID id, long expectedVersion);
}
