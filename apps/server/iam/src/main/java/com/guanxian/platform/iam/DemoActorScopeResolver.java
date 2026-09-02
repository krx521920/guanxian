package com.guanxian.platform.iam;

import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "guanxian.security.mode", havingValue = "demo")
class DemoActorScopeResolver implements ActorScopeResolver {
    private final UUID associationId;
    private final UUID enterpriseId;
    private final boolean exposeLocalUserId;

    DemoActorScopeResolver(
            UUID associationId,
            UUID enterpriseId) {
        this(associationId, enterpriseId, "memory");
    }

    @Autowired
    DemoActorScopeResolver(
            @Value("${guanxian.security.demo.association-id:00000000-0000-0000-0000-000000000106}") UUID associationId,
            @Value("${guanxian.security.demo.enterprise-id:00000000-0000-0000-0000-000000000201}") UUID enterpriseId,
            @Value("${guanxian.member.repository:postgres}") String memberRepository) {
        this.associationId = associationId;
        this.enterpriseId = enterpriseId;
        this.exposeLocalUserId = "memory".equalsIgnoreCase(memberRepository);
    }

    @Override
    public ActorScope resolve(Authentication authentication) {
        Set<String> roles = ActorScopes.roles(authentication);
        UUID boundEnterprise = roles.contains("ENTERPRISE_ADMIN") || roles.contains("ENTERPRISE_MEMBER")
                ? enterpriseId : null;
        UUID demoUserId = exposeLocalUserId
                ? UUID.nameUUIDFromBytes(
                        ("guanxian-demo-user:" + authentication.getName()).getBytes(StandardCharsets.UTF_8))
                : null;
        return new ActorScope(
                demoUserId,
                authentication.getName(),
                authentication.getName(),
                associationId,
                boundEnterprise,
                roles,
                Set.of());
    }
}
