package com.guanxian.platform.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties("guanxian.ai.rag")
public class RagProperties {
    private int chunkSizeChars = 1200;
    private int chunkOverlapChars = 150;
    private int retrievalLimit = 5;
    private int maxQuestionChars = 2000;
    private int maxInputTokens = 6000;
    private int maxOutputTokens = 800;
    private int maxDocumentChars = 2_000_000;
    private long maxDocumentBytes = 20L * 1024 * 1024;
    private BigDecimal maxEstimatedCost = new BigDecimal("0.50");
    private boolean externalModelDataEgressEnabled = false;

    public int getChunkSizeChars() { return chunkSizeChars; }
    public void setChunkSizeChars(int value) { chunkSizeChars = value; }
    public int getChunkOverlapChars() { return chunkOverlapChars; }
    public void setChunkOverlapChars(int value) { chunkOverlapChars = value; }
    public int getRetrievalLimit() { return retrievalLimit; }
    public void setRetrievalLimit(int value) { retrievalLimit = value; }
    public int getMaxQuestionChars() { return maxQuestionChars; }
    public void setMaxQuestionChars(int value) { maxQuestionChars = value; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int value) { maxInputTokens = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public int getMaxDocumentChars() { return maxDocumentChars; }
    public void setMaxDocumentChars(int value) { maxDocumentChars = value; }
    public long getMaxDocumentBytes() { return maxDocumentBytes; }
    public void setMaxDocumentBytes(long value) { maxDocumentBytes = value; }
    public BigDecimal getMaxEstimatedCost() { return maxEstimatedCost; }
    public void setMaxEstimatedCost(BigDecimal value) { maxEstimatedCost = value; }
    public boolean isExternalModelDataEgressEnabled() { return externalModelDataEgressEnabled; }
    public void setExternalModelDataEgressEnabled(boolean value) { externalModelDataEgressEnabled = value; }

    public void validate() {
        if (chunkSizeChars < 200 || chunkSizeChars > 8000) throw new IllegalStateException("rag chunk size must be between 200 and 8000");
        if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkSizeChars / 2) throw new IllegalStateException("rag overlap must be non-negative and less than half the chunk size");
        if (retrievalLimit < 1 || retrievalLimit > 12) throw new IllegalStateException("rag retrieval limit must be between 1 and 12");
        if (maxQuestionChars < 10 || maxInputTokens < 100 || maxOutputTokens < 1) throw new IllegalStateException("rag limits are invalid");
        if (maxDocumentChars < 1_000 || maxDocumentChars > 5_000_000
                || maxDocumentBytes < 1_024 || maxDocumentBytes > 100L * 1024 * 1024) {
            throw new IllegalStateException("rag document limits are invalid");
        }
        if (maxEstimatedCost == null || maxEstimatedCost.signum() < 0) throw new IllegalStateException("rag cost limit must be non-negative");
    }
}
