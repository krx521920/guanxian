package com.guanxian.platform.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.math.BigDecimal;

@ConfigurationProperties("guanxian.ai.embedding")
public class EmbeddingProperties {
    private boolean enabled;
    private String endpoint = "";
    private String apiKey = "";
    private String model = "";
    private int dimensions = 1536;
    private int maxBatchSize = 32;
    private Duration requestTimeout = Duration.ofSeconds(30);
    private BigDecimal costPerMillionTokens = BigDecimal.ZERO;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint.trim(); }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model.trim(); }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public BigDecimal getCostPerMillionTokens() { return costPerMillionTokens; }
    public void setCostPerMillionTokens(BigDecimal value) {
        this.costPerMillionTokens = value == null ? BigDecimal.ZERO : value;
    }
}
