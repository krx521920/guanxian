package com.guanxian.platform.member.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberDirectory {
    List<MemberProfile> findAll(String query);

    Optional<MemberProfile> findById(UUID id);
}
