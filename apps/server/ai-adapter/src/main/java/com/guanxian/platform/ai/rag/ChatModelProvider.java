package com.guanxian.platform.ai.rag;

import java.math.BigDecimal;
import java.util.List;

public interface ChatModelProvider {
    String providerName();
    boolean enabled();
    ChatResult complete(ChatRequest request);

    record Message(String role, String content) {
        public Message {
            if (role == null || role.isBlank() || content == null || content.isBlank()) {
                throw new IllegalArgumentException("message role and content are required");
            }
        }
    }

    record ChatRequest(List<Message> messages, int maxOutputTokens) {
        public ChatRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            if (messages.isEmpty() || maxOutputTokens < 1) throw new IllegalArgumentException("chat request is invalid");
        }
    }

    record ChatResult(String content, String model, int inputTokens, int outputTokens,
                      BigDecimal estimatedCost, String requestId, long latencyMs) {
        public ChatResult {
            if (content == null || content.isBlank()) throw new IllegalArgumentException("model returned an empty answer");
            estimatedCost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        }
    }
}
