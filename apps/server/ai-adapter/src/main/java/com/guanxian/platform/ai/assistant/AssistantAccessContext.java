package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.shared.security.ActorScope;

import java.util.Set;

/**
 * Immutable authorization snapshot copied from the authenticated request.
 * It is carried through Spring AI ToolContext and is never supplied by the model.
 */
public record AssistantAccessContext(ActorScope actor, Set<String> authorities) {
    public static final String TOOL_CONTEXT_KEY = "assistantAccess";

    public AssistantAccessContext {
        if (actor == null) throw new IllegalArgumentException("actor scope is required");
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(authority);
    }
}
