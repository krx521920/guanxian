package com.guanxian.platform.ai.assistant;

import java.util.Optional;

/**
 * Provides deterministic, permission-scoped answers when no external chat model is available.
 * Implementations must remain read-only and must not infer a broader scope than the supplied access snapshot.
 */
public interface AssistantLocalQueryProvider {
    Optional<LocalQueryResult> answer(LocalQueryRequest request);

    /** Optional scoped preflight for explicit user selection, for both model and local modes. */
    default Optional<LocalQueryResult> selected(AssistantAccessContext access, java.util.List<java.util.UUID> enterpriseIds) {
        return Optional.empty();
    }

    record LocalQueryRequest(
            AssistantAccessContext access,
            String message,
            String pageTitle,
            String pagePath) {
    }

    record LocalQueryResult(String answer, String mode, java.util.List<AssistantBusinessResults.Result> businessResults) {
        public LocalQueryResult(String answer, String mode) { this(answer, mode, java.util.List.of()); }
        public LocalQueryResult {
            businessResults = java.util.List.copyOf(businessResults);
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("local assistant answer is required");
            }
            if (mode == null || mode.isBlank()) {
                throw new IllegalArgumentException("local assistant mode is required");
            }
        }
    }
}
