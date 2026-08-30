package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeTextDocument;
import com.guanxian.platform.ai.rag.PolicyRagService.RagQuestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
    void privilegedGlobalScopeReadsAllAssociationsWhileSelectedAssociationRemainsNarrow() {
        RagProperties properties = new RagProperties();
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(repository, properties);
        UUID associationA = UUID.randomUUID();
        UUID associationB = UUID.randomUUID();
        ingestion.ingest(new KnowledgeTextDocument(
                null, associationA, "甲协会巡检规则", "POLICY", "MANUAL", null,
                "PRIVATE", "PUBLISHED", "author-a", "天穹校验标记要求甲协会每周巡检。"));
        ingestion.ingest(new KnowledgeTextDocument(
                null, associationB, "乙协会巡检规则", "POLICY", "MANUAL", null,
                "PRIVATE", "PUBLISHED", "author-b", "天穹校验标记要求乙协会每日巡检。"));

        var global = repository.retrieve(
                new KnowledgeRepository.RetrievalScope(null, "system-admin", true),
                "天穹校验标记巡检", 10);
        var associationOnly = repository.retrieve(
                new KnowledgeRepository.RetrievalScope(associationA, "system-admin", true),
                "天穹校验标记巡检", 10);

        assertEquals(Set.of("甲协会巡检规则", "乙协会巡检规则"), global.stream()
                .map(KnowledgeRepository.RetrievedChunk::documentTitle).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("甲协会巡检规则"), associationOnly.stream()
                .map(KnowledgeRepository.RetrievedChunk::documentTitle).distinct().toList());
    }

    @Test
    void memoryRepositoryRejectsCrossAssociationVersionUpdate() {
        RagProperties properties = new RagProperties();
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        KnowledgeIngestionService service = new KnowledgeIngestionService(repository, properties);
        UUID ownerAssociation = UUID.randomUUID();
        var initial = service.ingest(new KnowledgeTextDocument(
                null, ownerAssociation, "内部制度", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "tester", "地下管线设施应按照规定开展定期巡检。"
        ));

        assertThrows(IllegalArgumentException.class, () -> service.ingest(new KnowledgeTextDocument(
                initial.documentId(), UUID.randomUUID(), "越权更新", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "attacker", "尝试覆盖其他协会的知识文档。"
        )));
    }

    @Test
    void memoryRepositoryRejectsUnknownDocumentVersionUpdate() {
        RagProperties properties = new RagProperties();
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                new MemoryKnowledgeRepository(), properties);

        assertThrows(IllegalArgumentException.class, () -> service.ingest(new KnowledgeTextDocument(
                UUID.randomUUID(), UUID.randomUUID(), "不存在的文档", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "tester", "不能通过指定随机编号创建文档。"
        )));
    }

    @Test
    void privateDocumentIsVisibleOnlyToCreatorOrPrivilegedStaff() {
        RagProperties properties = new RagProperties();
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        UUID associationId = UUID.randomUUID();
        KnowledgeIngestionService ingestion = new KnowledgeIngestionService(repository, properties);
        var initial = ingestion.ingest(new KnowledgeTextDocument(
                null, associationId, "私有研判材料", "POLICY", "MANUAL", null,
                "PRIVATE", "PUBLISHED", "document-owner",
                "北区试验管线使用专属代号青铜松树并执行每日复核。"
        ));
        ingestion.ingest(new KnowledgeTextDocument(
                initial.documentId(), associationId, "私有研判材料修订版", "POLICY", "MANUAL", null,
                "PRIVATE", "PUBLISHED", "editing-subject",
                "北区试验管线使用专属代号青铜松树并执行每日压力复核。"
        ));
        PolicyRagService service = new PolicyRagService(repository, disabledProvider(), properties);

        var owner = service.ask(new RagQuestion(
                associationId, "document-owner", "青铜松树如何复核", 2, null));
        var editorWithoutStaffRole = service.ask(new RagQuestion(
                associationId, "editing-subject", "青铜松树如何复核", 2, null));
        var ordinaryMember = service.ask(new RagQuestion(
                associationId, "different-member", "青铜松树如何复核", 2, null));
        var associationStaff = service.ask(new RagQuestion(
                associationId, "association-operator", "青铜松树如何复核", 2, null, true));

        assertEquals("RETRIEVAL_SUMMARY", owner.mode());
        assertEquals(2, owner.citations().getFirst().version());
        assertEquals("NO_EVIDENCE", editorWithoutStaffRole.mode());
        assertEquals("NO_EVIDENCE", ordinaryMember.mode());
        assertTrue(ordinaryMember.citations().isEmpty());
        assertEquals("RETRIEVAL_SUMMARY", associationStaff.mode());
    }

    @Test
    void enabledProviderCannotReceiveRetrievedDataWithoutExplicitEgressApproval() {
        RagProperties properties = new RagProperties();
        assertFalse(properties.isExternalModelDataEgressEnabled());
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        UUID associationId = UUID.randomUUID();
        new KnowledgeIngestionService(repository, properties).ingest(new KnowledgeTextDocument(
                null, associationId, "内部政策", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "tester",
                "银杏编号管线需要在雨季开始前完成专项巡检。"
        ));
        AtomicBoolean called = new AtomicBoolean();
        ChatModelProvider enabledProvider = new ChatModelProvider() {
            public String providerName() { return "must-not-receive-data"; }
            public boolean enabled() { return true; }
            public ChatResult complete(ChatRequest request) {
                called.set(true);
                throw new AssertionError("external provider must not receive context");
            }
        };

        PolicyRagService service = new PolicyRagService(repository, enabledProvider, properties);
        var answer = service.ask(new RagQuestion(
                associationId, "member", "银杏编号管线何时巡检", 2, null));

        assertEquals("RETRIEVAL_SUMMARY", answer.mode());
        assertFalse(called.get());
        assertEquals(0, repository.modelExecutionCount());
    }

    @Test
    void estimatedCostLimitStopsExternalCall() {
        RagProperties properties = new RagProperties();
        properties.setMaxEstimatedCost(new BigDecimal("0.01"));
        properties.setExternalModelDataEgressEnabled(true);
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
