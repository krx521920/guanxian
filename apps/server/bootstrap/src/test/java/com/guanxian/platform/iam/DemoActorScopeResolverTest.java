package com.guanxian.platform.iam;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DemoActorScopeResolverTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void demoIdentityUsesConfiguredScopeWithoutInventingDatabaseUserId() {
        DemoActorScopeResolver resolver = new DemoActorScopeResolver(ASSOCIATION, ENTERPRISE);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "association-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ASSOCIATION_ADMIN")));

        var actor = resolver.resolve(authentication);

        assertNull(actor.userId());
        assertEquals("association-admin", actor.subject());
        assertEquals(ASSOCIATION, actor.associationId());
        assertNull(actor.enterpriseId());
    }

    @Test
    void enterpriseDemoIdentityReceivesConfiguredEnterpriseScope() {
        DemoActorScopeResolver resolver = new DemoActorScopeResolver(ASSOCIATION, ENTERPRISE);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "enterprise-admin", "", List.of(new SimpleGrantedAuthority("ROLE_ENTERPRISE_ADMIN")));

        var actor = resolver.resolve(authentication);

        assertEquals(ASSOCIATION, actor.associationId());
        assertEquals(ENTERPRISE, actor.enterpriseId());
    }
}
