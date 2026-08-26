package com.guanxian.platform.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties("guanxian.ai.provider")
public class AiProviderProperties {
    private boolean enabled = false;
    private String endpoint = "";
    private String apiKey = "";
    private String model = "";
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxOutputTokens = 800;
    private BigDecimal inputCostPerMillion = BigDecimal.ZERO;
    private BigDecimal outputCostPerMillion = BigDecimal.ZERO;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint == null ? "" : endpoint.trim(); }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "" : model.trim(); }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public BigDecimal getInputCostPerMillion() { return inputCostPerMillion; }
    public void setInputCostPerMillion(BigDecimal value) { this.inputCostPerMillion = value; }
    public BigDecimal getOutputCostPerMillion() { return outputCostPerMillion; }
    public void setOutputCostPerMillion(BigDecimal value) { this.outputCostPerMillion = value; }
}
