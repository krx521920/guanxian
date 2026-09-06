package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.RagProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
@Primary
public class PersonalizedAssistantChatClient implements AssistantChatClient {
    private final SpringAiAssistantChatClient platform;
    private final PersonalModelSettingsService settings;
    private final ChatMemory memory;
    private final List<AssistantToolProvider> tools;
    private final RagProperties rag;

    public PersonalizedAssistantChatClient(SpringAiAssistantChatClient platform, PersonalModelSettingsService settings,
                                           ChatMemory memory, List<AssistantToolProvider> tools, RagProperties rag) {
        this.platform = platform;
        this.settings = settings;
        this.memory = memory;
        this.tools = tools;
        this.rag = rag;
    }

    @Override
    public AssistantChatClient forAccess(AssistantAccessContext access) {
        if (!rag.isExternalModelDataEgressEnabled()) return platform;
        var saved = settings.active(access.actor().userId()).orElse(null);
        if (saved == null) return platform;
        var properties = settings.properties(saved);
        ChatClient client = SpringAiAssistantConfiguration.createClient(properties, memory);
        var factory = new StaticListableBeanFactory(Map.of("client", client));
        // No mutable global provider and no credentials cached across requests or users.
        return new SpringAiAssistantChatClient(factory.getBeanProvider(ChatClient.class), tools, properties, rag) {
            @Override public String providerName() { return "personal-" + saved.provider().name().toLowerCase(java.util.Locale.ROOT); }
            @Override public Completion complete(CompletionRequest request) { return super.complete(scoped(request)); }
            @Override public Flux<StreamChunk> stream(CompletionRequest request) { return super.stream(scoped(request)); }
            private CompletionRequest scoped(CompletionRequest request) {
                // Switching provider/model/key must not disclose previous-provider conversation history.
                return new CompletionRequest(request.access(), request.conversationKey() + ":" + saved.revision(),
                        request.prompt(), request.pageTitle(), request.pagePath(), request.userMessage(), request.businessResults());
            }
        };
    }

    @Override public boolean enabled() { return platform.enabled(); }
    @Override public String providerName() { return platform.providerName(); }
    @Override public BigDecimal estimateCost(int inputTokens, int outputTokens) { return platform.estimateCost(inputTokens, outputTokens); }
    @Override public Completion complete(CompletionRequest request) { return forAccess(request.access()).complete(request); }
    @Override public Flux<StreamChunk> stream(CompletionRequest request) { return forAccess(request.access()).stream(request); }
}
