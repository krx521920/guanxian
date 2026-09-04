package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.AiProviderProperties;
import com.guanxian.platform.ai.rag.DocumentTextChunker;
import com.guanxian.platform.ai.rag.RagProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;

@Component
public class SpringAiAssistantChatClient implements AssistantChatClient {
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final PlatformReadTools platformReadTools;
    private final AiProviderProperties providerProperties;
    private final RagProperties ragProperties;

    public SpringAiAssistantChatClient(
            @Qualifier("platformAssistantChatClient") ObjectProvider<ChatClient> chatClientProvider,
            PlatformReadTools platformReadTools,
            AiProviderProperties providerProperties,
            RagProperties ragProperties) {
        this.chatClientProvider = chatClientProvider;
        this.platformReadTools = platformReadTools;
        this.providerProperties = providerProperties;
        this.ragProperties = ragProperties;
    }

    @Override
    public boolean enabled() {
        return providerProperties.isEnabled()
                && ragProperties.isExternalModelDataEgressEnabled()
                && chatClientProvider.getIfAvailable() != null;
    }

    @Override
    public String providerName() {
        return "spring-ai-openai-compatible";
    }

    @Override
    public BigDecimal estimateCost(int inputTokens, int outputTokens) {
        BigDecimal input = providerProperties.getInputCostPerMillion()
                .multiply(BigDecimal.valueOf(Math.max(0, inputTokens)));
        BigDecimal output = providerProperties.getOutputCostPerMillion()
                .multiply(BigDecimal.valueOf(Math.max(0, outputTokens)));
        return input.add(output).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    @Override
    public Completion complete(CompletionRequest request) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (!enabled() || chatClient == null) {
            throw new IllegalStateException("Spring AI assistant is disabled");
        }
        long started = System.nanoTime();
        ChatResponse response = chatClient.prompt()
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, request.conversationKey()))
                .user(request.prompt())
                .tools(platformReadTools)
                .toolContext(Map.of(
                        PlatformReadTools.PAGE_PATH, request.pagePath(),
                        PlatformReadTools.PAGE_TITLE, request.pageTitle(),
                        "associationId", request.associationId().toString(),
                        "actorSubject", request.actorSubject()))
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Spring AI provider returned an empty response");
        }
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Spring AI provider returned an empty answer");
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int inputTokens = usage == null || usage.getPromptTokens() == null
                ? DocumentTextChunker.estimateTokens(request.prompt())
                : usage.getPromptTokens();
        int outputTokens = usage == null || usage.getCompletionTokens() == null
                ? DocumentTextChunker.estimateTokens(content)
                : usage.getCompletionTokens();
        String model = response.getMetadata() == null || response.getMetadata().getModel() == null
                ? providerProperties.getModel()
                : response.getMetadata().getModel();
        String requestId = response.getMetadata() == null ? null : response.getMetadata().getId();
        return new Completion(content.strip(), model, inputTokens, outputTokens,
                estimateCost(inputTokens, outputTokens), requestId,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }
}
