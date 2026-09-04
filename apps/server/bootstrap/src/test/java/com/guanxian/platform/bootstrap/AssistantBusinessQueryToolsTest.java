package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.AssistantAccessContext;
import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.ecosystem.EcosystemCatalogService;
import com.guanxian.platform.ecosystem.EcosystemMatchService;
import com.guanxian.platform.ecosystem.EcosystemPage;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantBusinessQueryToolsTest {
    private MemberService memberService;
    private EcosystemCatalogService catalogService;
    private EcosystemMatchService matchService;
    private CollaborationService collaborationService;
    private AssistantBusinessQueryTools tools;
    private ActorScope actor;

    @BeforeEach
    void setUp() {
        memberService = mock(MemberService.class);
        catalogService = mock(EcosystemCatalogService.class);
        matchService = mock(EcosystemMatchService.class);
        collaborationService = mock(CollaborationService.class);
        tools = new AssistantBusinessQueryTools(
                memberService,
                catalogService,
                matchService,
                collaborationService);
        actor = new ActorScope(
                null, "actor-1", "operator", UUID.randomUUID(), null,
                Set.of("ASSOCIATION_OPERATOR"), Set.of());
    }

    @Test
    void authoritySnapshotBlocksToolBeforeBusinessQuery() {
        var result = tools.searchMemberEnterprises("监测", context(Set.of("POLICY_READ")));

        assertThat(result.status()).isEqualTo("FORBIDDEN");
        assertThat(result.items()).isEmpty();
        verify(memberService, never()).findAll(any(), any(), anyBoolean(), any());
    }

    @Test
    void memberToolUsesActorScopeAndOmitsSensitiveContactFields() {
        MemberProfile member = new MemberProfile(
                UUID.randomUUID(), actor.associationId(), "京城管网科技", "91110000SECRET0001",
                "技术服务", "北京市", "张工", "13800000000", "zhang@example.cn", "简介",
                List.of("管线监测"), List.of("监测平台"), List.of("数据服务"), List.of("燃气"),
                List.of(), "MEMBERS", "ACTIVE", 1, Instant.EPOCH, Instant.EPOCH,
                null, null, null);
        when(memberService.findAll("监测", null, false, actor)).thenReturn(List.of(member));

        var result = tools.searchMemberEnterprises("监测", context(Set.of("MEMBER_READ")));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.items()).hasSize(1);
        String serializedShape = result.items().getFirst().toString();
        assertThat(serializedShape).contains("京城管网科技", "管线监测");
        assertThat(serializedShape).doesNotContain("13800000000", "zhang@example.cn", "91110000SECRET0001");
        verify(memberService).findAll("监测", null, false, actor);
    }

    @Test
    void catalogMatchAndCollaborationToolsReuseScopedReadServices() {
        when(catalogService.offerings(actor, "监测", false, 0, 10))
                .thenReturn(new EcosystemPage<>(List.of(), 0, 0, 10));
        when(catalogService.demands(actor, "合作", false, 0, 10))
                .thenReturn(new EcosystemPage<>(List.of(), 0, 0, 10));
        when(matchService.persistedReadOnly(actor, 0, 10, "NEGOTIATING"))
                .thenReturn(new EcosystemPage<>(List.of(), 0, 0, 10));
        when(collaborationService.page(actor, "巡检", "IN_PROGRESS", false, 0, 10))
                .thenReturn(new com.guanxian.platform.collaboration.CollaborationPage<>(
                        List.of(), 0, 0, 10));

        assertThat(tools.searchProductServices("监测", context(Set.of("MEMBER_READ"))).status())
                .isEqualTo("OK");
        assertThat(tools.searchBusinessDemands("合作", context(Set.of("MEMBER_READ"))).status())
                .isEqualTo("OK");
        assertThat(tools.listEcosystemMatches(
                "negotiating", context(Set.of("MATCH_REQUEST"))).status()).isEqualTo("OK");
        assertThat(tools.searchCollaborationItems(
                "巡检", "in_progress", context(Set.of("COLLABORATION_READ"))).status()).isEqualTo("OK");

        verify(catalogService).offerings(actor, "监测", false, 0, 10);
        verify(catalogService).demands(actor, "合作", false, 0, 10);
        verify(matchService).persistedReadOnly(actor, 0, 10, "NEGOTIATING");
        verify(collaborationService).page(actor, "巡检", "IN_PROGRESS", false, 0, 10);
    }

    private ToolContext context(Set<String> authorities) {
        return new ToolContext(Map.of(
                AssistantAccessContext.TOOL_CONTEXT_KEY,
                new AssistantAccessContext(actor, authorities)));
    }
}
