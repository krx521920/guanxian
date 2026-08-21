package com.guanxian.platform.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleChatModelProviderTest {
    @Test
    void externalProviderIsDisabledByDefaultAndCannotBeCalled() {
        AiProviderProperties properties = new AiProviderProperties();
        OpenAiCompatibleChatModelProvider provider = new OpenAiCompatibleChatModelProvider(properties, new ObjectMapper());

        assertDoesNotThrow(provider::afterPropertiesSet);
        assertFalse(provider.enabled());
        assertThrows(OpenAiCompatibleChatModelProvider.ExternalModelDisabledException.class,
                () -> provider.complete(new ChatModelProvider.ChatRequest(
                        java.util.List.of(new ChatModelProvider.Message("user", "问题")), 10)));
    }

    @Test
    void enabledProviderFailsClosedForInsecureOrPlaceholderConfiguration() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://example.test/v1/chat/completions");
        properties.setApiKey("change_me");
        properties.setModel("model");
        OpenAiCompatibleChatModelProvider provider = new OpenAiCompatibleChatModelProvider(properties, new ObjectMapper());

        assertThrows(IllegalStateException.class, provider::afterPropertiesSet);
    }

    @Test
    void estimatesCostUsingConfiguredPrices() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setInputCostPerMillion(new BigDecimal("2.00"));
        properties.setOutputCostPerMillion(new BigDecimal("8.00"));
        OpenAiCompatibleChatModelProvider provider = new OpenAiCompatibleChatModelProvider(properties, new ObjectMapper());

        assertEquals(new BigDecimal("0.01000000"), provider.estimateCost(1000, 1000));
    }
}
