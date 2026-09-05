package com.guanxian.platform.ai.assistant;

import java.math.BigDecimal;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AssistantChatClient {
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
            String pagePath) {
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
