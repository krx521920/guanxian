package com.guanxian.platform.ai.assistant;

import java.math.BigDecimal;
import java.util.UUID;

public interface AssistantChatClient {
    boolean enabled();

    String providerName();

    BigDecimal estimateCost(int inputTokens, int outputTokens);

    Completion complete(CompletionRequest request);

    record CompletionRequest(
            UUID associationId,
            String actorSubject,
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
}
