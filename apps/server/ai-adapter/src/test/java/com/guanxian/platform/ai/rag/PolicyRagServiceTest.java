package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeTextDocument;
import com.guanxian.platform.ai.rag.PolicyRagService.RagQuestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PolicyRagServiceTest {
    @Test
    void disabledExternalModelReturnsTraceableRetrievedSummary() {
        RagProperties properties = new RagProperties();
        properties.setChunkSizeChars(200);
        properties.setChunkOverlapChars(20);
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        UUID associationId = UUID.randomUUID();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(repository, properties);
        ingestion.ingest(new KnowledgeTextDocument(
                null, associationId, "地下管线安全管理办法", "POLICY", "URL",
                "https://example.gov.cn/policy/1", "ASSOCIATION", "PUBLISHED", "tester",
                "地下管线安全管理要求运营单位建立巡检制度，及时处置安全隐患并保存完整记录。"
        ));
        PolicyRagService service = new PolicyRagService(repository, disabledProvider(), properties);

        var answer = service.ask(new RagQuestion(
                associationId, "user-1", "地下管线安全管理有哪些要求？", 3, "request-1"));

        assertEquals("RETRIEVAL_SUMMARY", answer.mode());
        assertFalse(answer.citations().isEmpty());
        assertTrue(answer.answer().contains("[1]"));
        assertEquals("地下管线安全管理办法", answer.citations().getFirst().documentName());
        assertEquals(1, answer.citations().getFirst().version());
        assertEquals("https://example.gov.cn/policy/1", answer.citations().getFirst().source());
        assertNotNull(answer.traceId());
        assertEquals(1, repository.retrievalTraceCount());
        assertEquals(0, repository.modelExecutionCount());
    }

    @Test
    void associationScopedDocumentIsNotVisibleToAnotherAssociation() {
        RagProperties properties = new RagProperties();
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        new KnowledgeIngestionService(repository, properties).ingest(new KnowledgeTextDocument(
                null, UUID.randomUUID(), "内部政策", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "tester", "燃气地下管线需要每周巡检并登记。"
        ));
        PolicyRagService service = new PolicyRagService(repository, disabledProvider(), properties);

        var answer = service.ask(new RagQuestion(UUID.randomUUID(), "user-2", "燃气地下管线巡检要求", 3, null));

        assertEquals("NO_EVIDENCE", answer.mode());
        assertTrue(answer.citations().isEmpty());
        assertTrue(answer.answer().contains("无法形成"));
    }

    @Test
    void estimatedCostLimitStopsExternalCall() {
        RagProperties properties = new RagProperties();
        properties.setMaxEstimatedCost(new BigDecimal("0.01"));
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        new KnowledgeIngestionService(repository, properties).ingest(new KnowledgeTextDocument(
                null, null, "公开政策", "POLICY", "URL", "https://example.gov.cn/policy/2",
                "PUBLIC", "PUBLISHED", "tester", "供水地下管线运行单位应定期排查泄漏风险。"
        ));
        AtomicBoolean called = new AtomicBoolean();
        ChatModelProvider expensive = new ChatModelProvider() {
            public String providerName() { return "expensive"; }
            public boolean enabled() { return true; }
            public BigDecimal estimateCost(int inputTokens, int outputTokens) { return new BigDecimal("1.00"); }
            public ChatResult complete(ChatRequest request) { called.set(true); throw new AssertionError("must not call provider"); }
        };

        PolicyRagService service = new PolicyRagService(repository, expensive, properties);
        assertThrows(PolicyRagService.RagLimitException.class,
                () -> service.ask(new RagQuestion(null, "user-3", "供水地下管线泄漏风险要求", 2, null)));
        assertFalse(called.get());
    }

    private ChatModelProvider disabledProvider() {
        return new ChatModelProvider() {
            public String providerName() { return "disabled"; }
            public boolean enabled() { return false; }
            public ChatResult complete(ChatRequest request) { throw new AssertionError("disabled provider must not be called"); }
        };
    }
}
