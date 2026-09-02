package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeActor;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeTextDocument;
import com.guanxian.platform.ai.rag.RagEvaluationService.EvaluationCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationServiceTest {
    @Test
    void readinessDefaultsToManagementPlatformUntilAnEvaluationPasses() {
        var fixture = fixture();

        var readiness = fixture.evaluationService().readiness(fixture.associationId());

        assertFalse(readiness.ready());
        assertEquals("ASSOCIATION_COLLABORATION_PLATFORM", readiness.allowedProductLabel());
        assertTrue(readiness.reason().contains("no real-data RAG evaluation"));
    }

    @Test
    void realEvidenceAndRefusalDatasetCanOpenTheReleaseGate() {
        var fixture = fixture();
        var ingested = fixture.ingestionService().ingest(new KnowledgeTextDocument(
                null, fixture.associationId(), "燃气管线巡检制度", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "reviewer",
                "天衡巡检制度要求燃气地下管线每周巡检，发现泄漏隐患后立即登记并处置。"));
        List<EvaluationCase> cases = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            cases.add(new EvaluationCase(
                    "天衡巡检制度第" + index + "个评测问题：燃气地下管线的巡检要求是什么？",
                    List.of(ingested.documentId()), false));
        }
        cases.add(new EvaluationCase("玄武星海航行器的年度燃料配额是多少？", List.of(), true));

        var run = fixture.evaluationService().evaluate("协会真实资料评测集-v1", cases, fixture.actor());
        var readiness = fixture.evaluationService().readiness(fixture.associationId());

        assertTrue(run.passed());
        assertTrue(run.evidenceRecall().compareTo(RagEvaluationService.MIN_EVIDENCE_RECALL) >= 0);
        assertTrue(readiness.ready());
        assertEquals("AI_PLATFORM", readiness.allowedProductLabel());
        assertEquals(run.id(), readiness.latestRun().id());
    }

    @Test
    void evidenceFromAnotherAssociationCannotEnterTheDataset() {
        var fixture = fixture();
        UUID otherAssociation = UUID.randomUUID();
        var foreignDocument = fixture.ingestionService().ingest(new KnowledgeTextDocument(
                null, otherAssociation, "其他协会内部制度", "POLICY", "MANUAL", null,
                "ASSOCIATION", "PUBLISHED", "foreign-reviewer", "其他协会专用巡检证据。"));

        assertThrows(IllegalArgumentException.class, () -> fixture.evaluationService().evaluate(
                "越权评测集",
                List.of(new EvaluationCase("其他协会的巡检要求是什么？",
                        List.of(foreignDocument.documentId()), false)),
                fixture.actor()));
    }

    private Fixture fixture() {
        RagProperties properties = new RagProperties();
        MemoryKnowledgeRepository repository = new MemoryKnowledgeRepository();
        KnowledgeIngestionService ingestionService = new KnowledgeIngestionService(repository, properties);
        PolicyRagService ragService = new PolicyRagService(repository, disabledProvider(), properties);
        RagEvaluationService evaluationService = new RagEvaluationService(
                new MemoryRagEvaluationStore(), ragService, ingestionService);
        UUID associationId = UUID.randomUUID();
        KnowledgeActor actor = new KnowledgeActor(
                associationId, UUID.randomUUID(), "association-admin", "协会管理员", true, "evaluation-test");
        return new Fixture(associationId, actor, ingestionService, evaluationService);
    }

    private ChatModelProvider disabledProvider() {
        return new ChatModelProvider() {
            public String providerName() { return "disabled"; }
            public boolean enabled() { return false; }
            public ChatResult complete(ChatRequest request) {
                throw new AssertionError("disabled provider must not be called");
            }
        };
    }

    private record Fixture(
            UUID associationId,
            KnowledgeActor actor,
            KnowledgeIngestionService ingestionService,
            RagEvaluationService evaluationService) {
    }
}
