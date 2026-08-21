package com.guanxian.platform.member.api;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberDirectory {
    List<MemberProfile> findAll(String query, ActorScope actor);

    Optional<MemberProfile> findById(UUID id, ActorScope actor);
}
