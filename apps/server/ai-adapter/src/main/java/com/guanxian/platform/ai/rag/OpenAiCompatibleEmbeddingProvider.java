package com.guanxian.platform.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider, InitializingBean {
    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public OpenAiCompatibleEmbeddingProvider(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenAiCompatibleEmbeddingProvider(
            EmbeddingProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled()) return;
        URI endpoint;
        try {
            endpoint = URI.create(properties.getEndpoint());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("embedding endpoint is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null) {
            throw new IllegalStateException("enabled embedding provider requires an HTTPS endpoint");
        }
        String apiKey = properties.getApiKey().toLowerCase(Locale.ROOT);
        if (apiKey.length() < 16 || apiKey.contains("change_me") || apiKey.contains("placeholder")) {
            throw new IllegalStateException("enabled embedding provider requires a non-placeholder API key");
        }
        if (properties.getModel().isBlank()) {
            throw new IllegalStateException("enabled embedding provider requires a model");
        }
        if (properties.getDimensions() < 8 || properties.getDimensions() > 4096) {
            throw new IllegalStateException("embedding dimensions must be between 8 and 4096");
        }
        if (properties.getMaxBatchSize() < 1 || properties.getMaxBatchSize() > 128) {
            throw new IllegalStateException("embedding batch size must be between 1 and 128");
        }
        if (properties.getRequestTimeout() == null || properties.getRequestTimeout().isZero()
                || properties.getRequestTimeout().isNegative()) {
            throw new IllegalStateException("embedding request timeout must be positive");
        }
    }

    @Override public String providerName() { return "openai-compatible"; }
    @Override public String modelName() { return properties.getModel(); }
    @Override public int dimensions() { return enabled() ? properties.getDimensions() : 0; }
    @Override public boolean enabled() { return properties.isEnabled(); }

    @Override
    public List<double[]> embed(List<String> inputs) {
        if (!enabled()) throw new EmbeddingException("embedding provider is disabled");
        if (inputs == null || inputs.isEmpty()) return List.of();
        List<double[]> result = new ArrayList<>(inputs.size());
        for (int offset = 0; offset < inputs.size(); offset += properties.getMaxBatchSize()) {
            int end = Math.min(inputs.size(), offset + properties.getMaxBatchSize());
            result.addAll(embedBatch(inputs.subList(offset, end)));
        }
        return List.copyOf(result);
    }

    private List<double[]> embedBatch(List<String> inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("input", inputs);
        payload.put("dimensions", properties.getDimensions());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(properties.getRequestTimeout())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbeddingException("embedding provider returned HTTP " + response.statusCode());
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != inputs.size()) {
                throw new EmbeddingException("embedding provider returned an invalid batch size");
            }
            List<IndexedVector> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode embedding = item.path("embedding");
                if (index < 0 || !embedding.isArray() || embedding.size() != properties.getDimensions()) {
                    throw new EmbeddingException("embedding provider returned an invalid vector");
                }
                double[] vector = new double[embedding.size()];
                double magnitude = 0;
                for (int coordinate = 0; coordinate < vector.length; coordinate++) {
                    vector[coordinate] = embedding.get(coordinate).asDouble(Double.NaN);
                    if (!Double.isFinite(vector[coordinate])) {
                        throw new EmbeddingException("embedding provider returned a non-finite vector");
                    }
                    magnitude += vector[coordinate] * vector[coordinate];
                }
                if (magnitude == 0) throw new EmbeddingException("embedding provider returned a zero vector");
                vectors.add(new IndexedVector(index, vector));
            }
            vectors.sort(Comparator.comparingInt(IndexedVector::index));
            for (int index = 0; index < vectors.size(); index++) {
                if (vectors.get(index).index() != index) {
                    throw new EmbeddingException("embedding provider returned duplicate or missing indices");
                }
            }
            return vectors.stream().map(IndexedVector::vector).toList();
        } catch (EmbeddingException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("embedding request was interrupted", exception);
        } catch (Exception exception) {
            throw new EmbeddingException("embedding request failed", exception);
        }
    }

    private record IndexedVector(int index, double[] vector) {}

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}
