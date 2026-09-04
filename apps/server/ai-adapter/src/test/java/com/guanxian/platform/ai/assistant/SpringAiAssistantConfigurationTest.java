package com.guanxian.platform.ai.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringAiAssistantConfigurationTest {
    @Test
    void splitsFullCompatibleEndpointIntoBaseUrlAndCompletionPath() {
        var endpoint = SpringAiAssistantConfiguration.EndpointParts.from(
                "https://models.example.cn/openai/v1/chat/completions");

        assertEquals("https://models.example.cn", endpoint.baseUrl());
        assertEquals("/openai/v1/chat/completions", endpoint.completionsPath());
    }

    @Test
    void rejectsNonHttpsOrAmbiguousEndpoints() {
        assertThrows(IllegalStateException.class,
                () -> SpringAiAssistantConfiguration.EndpointParts.from("http://models.example.cn/v1/chat/completions"));
        assertThrows(IllegalStateException.class,
                () -> SpringAiAssistantConfiguration.EndpointParts.from("https://models.example.cn"));
        assertThrows(IllegalStateException.class,
                () -> SpringAiAssistantConfiguration.EndpointParts.from(
                        "https://models.example.cn/v1/chat/completions?api_key=secret"));
    }
}
