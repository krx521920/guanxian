package com.guanxian.platform.tender;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.List;
import java.util.UUID;

/**
 * Port that supplies member-facing enterprise data to the tender module without
 * depending on the member module's internal implementation. The bootstrap module
 * adapts this contract against {@code com.guanxian.platform.member.api.MemberDirectory}.
 */
public interface KeywordSource {
    List<String> keywordsFor(UUID enterpriseId, ActorScope actor);

    List<UUID> allEnterpriseIds(ActorScope actor);

    String enterpriseName(UUID enterpriseId, ActorScope actor);
}
