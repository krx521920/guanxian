package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.ChatModelProvider;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService;
import com.guanxian.platform.ai.rag.MemoryKnowledgeRepository;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.ai.rag.RagProperties;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformAssistantServiceTest {
    @Test
    void disabledAgentKeepsLocalGroundedFallbackAndConversationId() {
        Fixture fixture = fixture(false);
        UUID conversationId = UUID.randomUUID();

        var answer = fixture.service.chat(question(fixture.associationId, "actor-1", conversationId));

        assertEquals(conversationId, answer.conversationId());
        assertEquals("RETRIEVAL_SUMMARY", answer.mode());
        assertFalse(answer.modelConnected());
        assertFalse(answer.citations().isEmpty());
        assertTrue(answer.answer().contains("[1]"));
    }

    @Test
    void enabledAgentUsesSpringClientWithoutNestedRagCompletion() {
        Fixture fixture = fixture(true);
        UUID conversationId = UUID.randomUUID();

        var answer = fixture.service.chat(question(fixture.associationId, "actor-1", conversationId));

        assertEquals("SPRING_AI_AGENT", answer.mode());
        assertTrue(answer.modelConnected());
        assertEquals("请在会员企业页面选择批量导入。[1]", answer.answer());
        assertTrue(fixture.request.get().prompt().contains("当前权限范围内的检索证据"));
        assertFalse(fixture.request.get().prompt().contains("不允许被调用"));
    }

    @Test
    void streamEmitsStartDeltaAndAuditedCompletion() {
        Fixture fixture = fixture(true);
        UUID conversationId = UUID.randomUUID();

        var events = fixture.service.stream(
                question(fixture.associationId, "actor-1", conversationId)).collectList().block();

        assertEquals(List.of("start", "delta", "complete"),
                events.stream().map(PlatformAssistantService.AssistantStreamEvent::type).toList());
        assertEquals("请在会员企业页面选择批量导入。[1]", events.getLast().answer().answer());
        assertTrue(events.getLast().answer().modelConnected());
    }

    @Test
    void memoryKeyIsIsolatedByActorAssociationAndConversation() {
        UUID association = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        var first = question(association, "actor-1", conversation);
        var anotherActor = question(association, "actor-2", conversation);
        var anotherAssociation = question(UUID.randomUUID(), "actor-1", conversation);

        assertNotEquals(PlatformAssistantService.conversationKey(first),
                PlatformAssistantService.conversationKey(anotherActor));
        assertNotEquals(PlatformAssistantService.conversationKey(first),
                PlatformAssistantService.conversationKey(anotherAssociation));
    }

    private static Fixture fixture(boolean agentEnabled) {
        RagProperties properties = new RagProperties();
        properties.setChunkSizeChars(200);
        properties.setChunkOverlapChars(20);
        properties.setExternalModelDataEgressEnabled(true);
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        UUID associationId = UUID.randomUUID();
        new KnowledgeIngestionService(repository, properties).ingest(
                new KnowledgeIngestionService.KnowledgeTextDocument(
                        null, associationId, "会员服务手册", "POLICY", "MANUAL", null,
                        "ASSOCIATION", "PUBLISHED", "author",
                        "会员企业资料可以通过会员企业页面批量导入，导入前应先预览并校验错误。"));
        ChatModelProvider mustNotBeCalled = new ChatModelProvider() {
            public String providerName() { return "must-not-be-called"; }
            public boolean enabled() { return true; }
            public ChatResult complete(ChatRequest request) {
                throw new AssertionError("RAG model completion must not run inside the assistant flow");
            }
        };
        PolicyRagService ragService = new PolicyRagService(repository, mustNotBeCalled, properties);
        AtomicReference<AssistantChatClient.CompletionRequest> request = new AtomicReference<>();
        AssistantChatClient chatClient = new AssistantChatClient() {
            public boolean enabled() { return agentEnabled; }
            public String providerName() { return "spring-ai-test"; }
            public BigDecimal estimateCost(int inputTokens, int outputTokens) { return BigDecimal.ZERO; }
            public Completion complete(CompletionRequest value) {
                request.set(value);
                return new Completion("请在会员企业页面选择批量导入。[1]", "test-model",
                        80, 18, BigDecimal.ZERO, "provider-request", 12);
            }
        };
        return new Fixture(
                new PlatformAssistantService(ragService, chatClient, repository, properties),
                associationId, request);
    }

    private static PlatformAssistantService.AssistantQuestion question(
            UUID associationId, String actor, UUID conversationId) {
        ActorScope actorScope = new ActorScope(
                null, actor, actor, associationId, null, Set.of("ASSOCIATION_OPERATOR"), Set.of());
        return new PlatformAssistantService.AssistantQuestion(
                new AssistantAccessContext(actorScope, Set.of("POLICY_READ", "MEMBER_READ")),
                conversationId, "会员资料怎么批量导入？", 3,
                "会员企业", "/members", "request-1");
    }

    private record Fixture(
            PlatformAssistantService service,
            UUID associationId,
            AtomicReference<AssistantChatClient.CompletionRequest> request) {
    }
}
