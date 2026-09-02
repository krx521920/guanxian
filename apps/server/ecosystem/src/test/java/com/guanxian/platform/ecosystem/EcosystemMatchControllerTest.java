package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcosystemMatchControllerTest {
    @Test
    void listUsesTheAuthenticatedActorAndReturnsPersistedRecords() {
        EcosystemMatchService service = mock(EcosystemMatchService.class);
        ActorScopeResolver resolver = mock(ActorScopeResolver.class);
        Authentication authentication = mock(Authentication.class);
        ActorScope actor = new ActorScope(
                UUID.randomUUID(), "enterprise-subject", "enterprise-user",
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ENTERPRISE_ADMIN"), Set.of());
        PersistedMatchView persisted = persisted(actor.enterpriseId());
        when(resolver.resolve(authentication)).thenReturn(actor);
        when(service.list(actor)).thenReturn(List.of(persisted));

        ApiResponse<List<PersistedMatchView>> response =
                new EcosystemMatchController(service, resolver).list(authentication);

        assertEquals("OK", response.code());
        assertEquals(List.of(persisted), response.data());
        verify(resolver).resolve(authentication);
        verify(service).list(actor);
    }

    @Test
    void listKeepsARealEmptyRepositoryEmpty() {
        EcosystemMatchService service = mock(EcosystemMatchService.class);
        ActorScopeResolver resolver = mock(ActorScopeResolver.class);
        Authentication authentication = mock(Authentication.class);
        ActorScope actor = new ActorScope(
                UUID.randomUUID(), "association-subject", "association-user",
                UUID.randomUUID(), null, Set.of("ASSOCIATION_ADMIN"), Set.of());
        when(resolver.resolve(authentication)).thenReturn(actor);
        when(service.list(actor)).thenReturn(List.of());

        ApiResponse<List<PersistedMatchView>> response =
                new EcosystemMatchController(service, resolver).list(authentication);

        assertEquals(List.of(), response.data());
        verify(service).list(actor);
    }

    private static PersistedMatchView persisted(UUID demandEnterpriseId) {
        return new PersistedMatchView(
                UUID.randomUUID(), UUID.randomUUID(), demandEnterpriseId, UUID.randomUUID(),
                "需求企业", "真实需求", "燃气", "供应企业", "真实方案", 86,
                List.of("能力匹配"), "PENDING_CONFIRMATION", null, 0, Instant.now());
    }
}
