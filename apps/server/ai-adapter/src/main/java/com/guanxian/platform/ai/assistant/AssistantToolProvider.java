package com.guanxian.platform.ai.assistant;

/** Supplies one narrowly scoped object containing Spring AI {@code @Tool} methods. */
public interface AssistantToolProvider {
    Object toolObject();
}
