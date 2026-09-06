package com.guanxian.platform.ai.assistant;

import java.math.BigDecimal;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AssistantChatClient {
    /** Resolve once per request, using verified identity rather than thread-local servlet state. */
    default AssistantChatClient forAccess(AssistantAccessContext access) { return this; }

    boolean enabled();

    String providerName();

    BigDecimal estimateCost(int inputTokens, int outputTokens);

    Completion complete(CompletionRequest request);

    default Flux<StreamChunk> stream(CompletionRequest request) {
        return Mono.fromSupplier(() -> {
            Completion completion = complete(request);
            return new StreamChunk(
                    completion.content(), completion.model(), completion.inputTokens(), completion.outputTokens(),
                    completion.estimatedCost(), completion.providerRequestId(), completion.latencyMs());
        }).flux();
    }

    record CompletionRequest(
            AssistantAccessContext access,
            String conversationKey,
            String prompt,
            String pageTitle,
            String pagePath,
            String userMessage,
            AssistantBusinessResults businessResults) {
        public CompletionRequest(AssistantAccessContext access, String conversationKey, String prompt, String pageTitle, String pagePath, String userMessage) {
            this(access, conversationKey, prompt, pageTitle, pagePath, userMessage, new AssistantBusinessResults());
        }
        public CompletionRequest(AssistantAccessContext access, String conversationKey, String prompt, String pageTitle, String pagePath) {
            this(access, conversationKey, prompt, pageTitle, pagePath, prompt);
        }
    }

    record Completion(
            String content,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal estimatedCost,
            String providerRequestId,
            long latencyMs) {
    }

    record StreamChunk(
            String content,
            String model,
            int inputTokens,
            int outputTokens,
            BigDecimal estimatedCost,
            String providerRequestId,
            long latencyMs) {
    }
}
