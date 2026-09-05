package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.AssistantAccessContext;
import com.guanxian.platform.ai.assistant.AssistantChatClient;
import com.guanxian.platform.ai.assistant.PlatformAssistantService;
import com.guanxian.platform.ai.assistant.SpringAiAssistantChatClient;
import com.guanxian.platform.ai.rag.AiProviderProperties;
import com.guanxian.platform.ai.rag.ChatModelProvider;
import com.guanxian.platform.ai.rag.MemoryKnowledgeRepository;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.ai.rag.RagProperties;
import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.ecosystem.EcosystemCatalogService;
import com.guanxian.platform.ecosystem.EcosystemMatchService;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real Spring AI tool callback conversion and authorization context without making
 * a network model call: assistant service -> ChatClient -> model-selected tool -> scoped business
 * service -> streamed assistant completion.
 */
class AssistantToolCallingIntegrationTest {
    @Test
    void modelSelectedBusinessToolUsesScopedServiceAndStreamsItsResult() {
        MemberService memberService = mock(MemberService.class);
        ActorScope actor = new ActorScope(
                null, "actor-1", "operator", UUID.randomUUID(), null,
                Set.of("ASSOCIATION_OPERATOR"), Set.of());
        MemberProfile member = new MemberProfile(
                UUID.randomUUID(), actor.associationId(), "京城管网科技", "91110000SECRET0001",
                "技术服务", "北京市", "张工", "13800000000", "zhang@example.cn", "简介",
                List.of("管线监测"), List.of("监测平台"), List.of("数据服务"), List.of("燃气"),
                List.of(), "MEMBERS", "ACTIVE", 1, Instant.EPOCH, Instant.EPOCH,
                null, null, null);
        when(memberService.findAll("监测", null, false, actor)).thenReturn(List.of(member));

        AssistantBusinessQueryTools tools = new AssistantBusinessQueryTools(
                memberService,
                mock(EcosystemCatalogService.class),
                mock(EcosystemMatchService.class),
                mock(CollaborationService.class));
        ChatClient chatClient = ChatClient.builder(new ToolSelectingChatModel()).build();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("platformAssistantChatClient", chatClient);

        AiProviderProperties providerProperties = new AiProviderProperties();
        providerProperties.setEnabled(true);
        providerProperties.setModel("tool-integration-model");
        RagProperties ragProperties = new RagProperties();
        ragProperties.setExternalModelDataEgressEnabled(true);
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        ChatModelProvider unusedRagModel = new ChatModelProvider() {
            public String providerName() { return "disabled-rag-model"; }
            public boolean enabled() { return false; }
            public ChatResult complete(ChatRequest request) {
                throw new AssertionError("nested RAG completion must not run");
            }
        };
        SpringAiAssistantChatClient assistantClient = new SpringAiAssistantChatClient(
                beans.getBeanProvider(ChatClient.class), List.of(tools), providerProperties, ragProperties);
        PlatformAssistantService service = new PlatformAssistantService(
                new PolicyRagService(repository, unusedRagModel, ragProperties),
                assistantClient, repository, ragProperties, List.of(tools));
        UUID conversationId = UUID.randomUUID();
        AssistantAccessContext access = new AssistantAccessContext(actor, Set.of("POLICY_READ", "MEMBER_READ"));

        List<PlatformAssistantService.AssistantStreamEvent> events = service.stream(
                new PlatformAssistantService.AssistantQuestion(
                        access, conversationId, "查找有监测能力的会员企业", 5,
                        "会员企业", "/members", "request-tool-integration"))
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(PlatformAssistantService.AssistantStreamEvent::type)
                .containsExactly("start", "delta", "delta", "complete");
        var answer = events.getLast().answer();
        assertThat(answer.mode()).isEqualTo("SPRING_AI_AGENT");
        assertThat(answer.modelConnected()).isTrue();
        assertThat(answer.answer()).contains("京城管网科技", "管线监测");
        assertThat(answer.answer()).doesNotContain("13800000000", "91110000SECRET0001");
        verify(memberService).findAll("监测", null, false, actor);
    }

    private static final class ToolSelectingChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return responses(prompt).blockLast();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return responses(prompt);
        }

        private Flux<ChatResponse> responses(Prompt prompt) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            ToolCallback callback = options.getToolCallbacks().stream()
                    .filter(candidate -> "search_member_enterprises".equals(
                            candidate.getToolDefinition().name()))
                    .findFirst()
                    .orElseThrow();
            String toolResult = callback.call(
                    "{\"keyword\":\"监测\"}", new ToolContext(options.getToolContext()));
            assertThat(toolResult).contains("京城管网科技", "管线监测")
                    .doesNotContain("13800000000", "91110000SECRET0001");
            return Flux.just(
                    response("业务查询结果："),
                    response("京城管网科技具备管线监测能力。"));
        }

        private static ChatResponse response(String content) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
        }
    }
}
