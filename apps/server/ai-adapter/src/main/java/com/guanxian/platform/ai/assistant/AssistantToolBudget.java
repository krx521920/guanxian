package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import org.springframework.ai.chat.model.ToolContext;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-inference work budget; carried outside the model-visible argument schema. */
public final class AssistantToolBudget {
    public static final String CONTEXT_KEY = "guanxian.toolBudget";
    private final AtomicInteger calls;
    public AssistantToolBudget() { this(0); }
    public AssistantToolBudget(int completedQueries) {
        if (completedQueries < 0 || completedQueries > 8) throw new IllegalArgumentException("invalid completed query count");
        calls = new AtomicInteger(completedQueries);
    }
    public static void consume(ToolContext context) {
        Object budget = context == null ? null : context.getContext().get(CONTEXT_KEY);
        // Deterministic local queries do not execute a model loop and do not attach this value.
        if (budget instanceof AssistantToolBudget value && value.calls.incrementAndGet() > 8) {
            throw new RagLimitException("assistant read-only tool call budget exceeded");
        }
    }
}
