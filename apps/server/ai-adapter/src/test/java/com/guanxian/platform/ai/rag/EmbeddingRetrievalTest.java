package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeTextDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddingRetrievalTest {
    @Test
    void retrievesSemanticallyWhenQuestionSharesNoLiteralTerm() {
        RagProperties properties = new RagProperties();
        properties.setExternalModelDataEgressEnabled(true);
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        EmbeddingProvider embedding = constantEmbedding();
        UUID associationId = UUID.randomUUID();

        var ingested = new KnowledgeIngestionService(repository, properties, embedding).ingest(
                new KnowledgeTextDocument(null, associationId, "燃气运行规范", "POLICY", "MANUAL",
                        "https://policy.test/source", "ASSOCIATION", "PUBLISHED", "editor",
                        "燃气装置应进行每日巡检，并保存完整处置记录。"));

        PolicyRagService service = new PolicyRagService(
                repository, disabledChat(), properties, embedding);
        var answer = service.ask(new PolicyRagService.RagQuestion(
                associationId, "reader", "请解释隐藏概念", 3, "request-vector"));

        assertEquals("fake-embedding", ingested.embeddingProvider());
        assertEquals(8, ingested.embeddingDimensions());
        assertEquals("HYBRID_VECTOR", answer.retrievalMode());
        assertFalse(answer.citations().isEmpty());
        assertEquals("燃气运行规范", answer.citations().getFirst().documentName());
    }

    private EmbeddingProvider constantEmbedding() {
        return new EmbeddingProvider() {
            @Override public String providerName() { return "fake-embedding"; }
            @Override public String modelName() { return "fake-8d"; }
            @Override public int dimensions() { return 8; }
            @Override public boolean enabled() { return true; }
            @Override public List<double[]> embed(List<String> inputs) {
                return inputs.stream().map(ignored -> new double[]{1, 0, 0, 0, 0, 0, 0, 0}).toList();
            }
        };
    }

    private ChatModelProvider disabledChat() {
        return new ChatModelProvider() {
            @Override public String providerName() { return "disabled"; }
            @Override public boolean enabled() { return false; }
            @Override public ChatResult complete(ChatRequest request) { throw new UnsupportedOperationException(); }
            @Override public BigDecimal estimateCost(int inputTokens, int outputTokens) { return BigDecimal.ZERO; }
        };
    }
}
