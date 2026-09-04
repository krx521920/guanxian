package com.guanxian.platform.ai.assistant;

import java.util.Optional;

/**
 * Provides deterministic, permission-scoped answers when no external chat model is available.
 * Implementations must remain read-only and must not infer a broader scope than the supplied access snapshot.
 */
public interface AssistantLocalQueryProvider {
    Optional<LocalQueryResult> answer(LocalQueryRequest request);

    record LocalQueryRequest(
            AssistantAccessContext access,
            String message,
            String pageTitle,
            String pagePath) {
    }

    record LocalQueryResult(String answer, String mode) {
        public LocalQueryResult {
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("local assistant answer is required");
            }
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("local assistant mode is required");
            }
        }
    }
}
