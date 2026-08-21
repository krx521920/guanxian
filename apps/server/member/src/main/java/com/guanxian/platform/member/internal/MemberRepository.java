package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MemberRepository {
    List<MemberProfile> findAll();

    Optional<MemberProfile> findById(UUID id);

    UUID defaultAssociationId();

    void insert(MemberProfile member);

    boolean update(MemberProfile member, long expectedVersion);

    boolean deleteById(UUID id, long expectedVersion);
}
