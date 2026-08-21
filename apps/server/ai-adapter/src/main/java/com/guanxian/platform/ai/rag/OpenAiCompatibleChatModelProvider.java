package com.guanxian.platform.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OpenAiCompatibleChatModelProvider implements ChatModelProvider, InitializingBean {
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final AiProviderProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleChatModelProvider(AiProviderProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenAiCompatibleChatModelProvider(AiProviderProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isEnabled()) return;
        URI endpoint;
        try {
            endpoint = URI.create(properties.getEndpoint());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AI provider endpoint is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IllegalStateException("enabled AI provider requires an HTTPS endpoint without user information");
        }
        if (isUnsafeSecret(properties.getApiKey())) throw new IllegalStateException("enabled AI provider requires a non-placeholder API key");
        if (properties.getModel().isBlank()) throw new IllegalStateException("enabled AI provider requires a model");
        if (properties.getRequestTimeout() == null || properties.getRequestTimeout().isZero() || properties.getRequestTimeout().isNegative()) {
            throw new IllegalStateException("AI provider request timeout must be positive");
        }
        if (properties.getMaxOutputTokens() < 1) throw new IllegalStateException("AI provider output token limit must be positive");
        if (properties.getInputCostPerMillion() == null || properties.getInputCostPerMillion().signum() < 0
                || properties.getOutputCostPerMillion() == null || properties.getOutputCostPerMillion().signum() < 0) {
            throw new IllegalStateException("AI provider prices must be non-negative");
        }
    }

    @Override
    public String providerName() {
        return "openai-compatible";
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public ChatResult complete(ChatRequest request) {
        if (!enabled()) throw new ExternalModelDisabledException("external AI provider is disabled");
        int outputLimit = Math.min(request.maxOutputTokens(), properties.getMaxOutputTokens());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", request.messages().stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        payload.put("temperature", 0.1);
        payload.put("max_tokens", outputLimit);

        long started = System.nanoTime();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(properties.getRequestTimeout())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiProviderException("AI provider returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int inputTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = root.path("usage").path("completion_tokens").asInt(0);
            String requestId = root.path("id").asText("");
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            return new ChatResult(content, properties.getModel(), inputTokens, outputTokens,
                    estimateCost(inputTokens, outputTokens), requestId, latencyMs);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("AI provider request was interrupted", exception);
        } catch (Exception exception) {
            throw new AiProviderException("AI provider request failed", exception);
        }
    }

    public BigDecimal estimateCost(int inputTokens, int outputTokens) {
        BigDecimal input = properties.getInputCostPerMillion().multiply(BigDecimal.valueOf(Math.max(0, inputTokens)));
        BigDecimal output = properties.getOutputCostPerMillion().multiply(BigDecimal.valueOf(Math.max(0, outputTokens)));
        return input.add(output).divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    private boolean isUnsafeSecret(String value) {
        if (value == null || value.isBlank()) return true;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("change_me") || normalized.contains("changeme")
                || normalized.contains("example") || normalized.contains("placeholder");
    }

    public static class ExternalModelDisabledException extends IllegalStateException {
        public ExternalModelDisabledException(String message) { super(message); }
    }

    public static class AiProviderException extends RuntimeException {
        public AiProviderException(String message) { super(message); }
        public AiProviderException(String message, Throwable cause) { super(message, cause); }
    }
}
