package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.PlatformAssistantService;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantControllerScopeTest {
    private static final UUID ASSOCIATION_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSOCIATION_B = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CONVERSATION = UUID.fromString("20000000-0000-4000-8000-000000000001");

    private PlatformAssistantService assistantService;
    private ActorScopeResolver actorScopeResolver;
    private Authentication authentication;
    private AssistantController controller;

    @BeforeEach
    void setUp() {
        assistantService = mock(PlatformAssistantService.class);
        actorScopeResolver = mock(ActorScopeResolver.class);
        authentication = mock(Authentication.class);
        controller = new AssistantController(assistantService, actorScopeResolver);
        org.mockito.Mockito.doReturn(List.of(new SimpleGrantedAuthority("POLICY_READ")))
                .when(authentication).getAuthorities();
        when(assistantService.chat(any())).thenReturn(new PlatformAssistantService.AssistantAnswer(
                "暂无证据", List.of(), UUID.randomUUID(), "NO_EVIDENCE", "LEXICAL",
                0, 0, BigDecimal.ZERO, CONVERSATION, false));
    }

    @Test
    void selectedAssociationIsAuthoritativeForAssistantChat() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(ASSOCIATION_A));

        controller.chat(request(ASSOCIATION_A), authentication);

        ArgumentCaptor<PlatformAssistantService.AssistantQuestion> question =
                ArgumentCaptor.forClass(PlatformAssistantService.AssistantQuestion.class);
        verify(assistantService).chat(question.capture());
        assertThat(question.getValue().access().actor().associationId()).isEqualTo(ASSOCIATION_A);
        assertThat(question.getValue().access().hasAuthority("POLICY_READ")).isTrue();
        assertThat(question.getValue().conversationId()).isEqualTo(CONVERSATION);
        assertThat(question.getValue().pagePath()).isEqualTo("/members");
    }

    @Test
    void streamDisablesProxyBufferingAndKeepsTheServerAuthorizationSnapshot() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(ASSOCIATION_A));
        when(assistantService.stream(any())).thenReturn(Flux.just(
                PlatformAssistantService.AssistantStreamEvent.start(CONVERSATION)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        var events = controller.stream(request(ASSOCIATION_A), authentication, response)
                .collectList().block();

        assertThat(events).hasSize(1);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache, no-transform");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        ArgumentCaptor<PlatformAssistantService.AssistantQuestion> question =
                ArgumentCaptor.forClass(PlatformAssistantService.AssistantQuestion.class);
        verify(assistantService).stream(question.capture());
        assertThat(question.getValue().access().actor().associationId()).isEqualTo(ASSOCIATION_A);
        assertThat(question.getValue().access().hasAuthority("POLICY_READ")).isTrue();
    }

    @Test
    void requestCannotOverrideSelectedSystemContext() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(ASSOCIATION_A));

        assertThatThrownBy(() -> controller.chat(request(ASSOCIATION_B), authentication))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("SYSTEM_CONTEXT_FORBIDDEN"));
        verify(assistantService, never()).chat(any());
    }

    @Test
    void unscopedSystemAdministratorCannotStartConversation() {
        when(actorScopeResolver.resolve(authentication)).thenReturn(systemAdmin(null));

        assertThatThrownBy(() -> controller.chat(request(null), authentication))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSOCIATION_CONTEXT_REQUIRED"));
        verify(assistantService, never()).chat(any());
    }

    private static AssistantController.AssistantChatRequest request(UUID associationId) {
        return new AssistantController.AssistantChatRequest(
                associationId, CONVERSATION, "批量导入在哪里？", 5, "会员企业", "/members");
    }

    private static ActorScope systemAdmin(UUID associationId) {
        return new ActorScope(null, "system-subject", "system-admin",
                associationId, null, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
