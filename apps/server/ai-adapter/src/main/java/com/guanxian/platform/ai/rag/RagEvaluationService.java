package com.guanxian.platform.ai.rag;

import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeActor;
import com.guanxian.platform.ai.rag.RagEvaluationStore.EvaluationDraft;
import com.guanxian.platform.ai.rag.RagEvaluationStore.EvaluationRun;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RagEvaluationService {
    static final int MIN_CASES = 10;
    static final BigDecimal MIN_EVIDENCE_RECALL = new BigDecimal("0.85");
    static final BigDecimal MIN_CITATION_PRECISION = new BigDecimal("0.95");
    static final BigDecimal MIN_REFUSAL_ACCURACY = new BigDecimal("0.95");

    private final RagEvaluationStore store;
    private final PolicyRagService ragService;
    private final KnowledgeIngestionService knowledgeService;

    public RagEvaluationService(
            RagEvaluationStore store,
            PolicyRagService ragService,
            KnowledgeIngestionService knowledgeService) {
        this.store = store;
        this.ragService = ragService;
        this.knowledgeService = knowledgeService;
    }

    public EvaluationRun evaluate(String datasetName, List<EvaluationCase> cases, KnowledgeActor actor) {
        String safeName = datasetName == null ? "" : datasetName.trim();
        if (safeName.isEmpty() || safeName.length() > 200) {
            throw new IllegalArgumentException("evaluation dataset name is required and must not exceed 200 characters");
        }
        if (cases == null || cases.isEmpty() || cases.size() > 200) {
            throw new IllegalArgumentException("evaluation dataset must contain between 1 and 200 cases");
        }
        int evidenceCases = 0;
        int evidenceHits = 0;
        int expectedRefusals = 0;
        int correctRefusals = 0;
        int totalCitations = 0;
        int correctCitations = 0;
        BigDecimal estimatedCost = BigDecimal.ZERO;
        List<Map<String, Object>> caseResults = new ArrayList<>();
        List<String> canonical = new ArrayList<>();

        for (int index = 0; index < cases.size(); index++) {
            EvaluationCase evaluationCase = validateCase(cases.get(index), actor);
            canonical.add(canonical(evaluationCase));
            PolicyRagService.RagAnswer answer = ragService.ask(new PolicyRagService.RagQuestion(
                    actor.associationId(), actor.subject(), evaluationCase.question(), 8,
                    actor.requestId() == null ? "rag-evaluation-" + index : actor.requestId() + "-eval-" + index,
                    true));
            estimatedCost = estimatedCost.add(answer.estimatedCost());
            Set<UUID> expected = Set.copyOf(evaluationCase.expectedDocumentIds());
            boolean refused = "NO_EVIDENCE".equals(answer.mode()) && answer.citations().isEmpty();
            boolean recalled = !expected.isEmpty() && answer.citations().stream()
                    .anyMatch(citation -> expected.contains(citation.documentId()));
            if (evaluationCase.expectRefusal()) {
                expectedRefusals++;
                if (refused) correctRefusals++;
            } else {
                evidenceCases++;
                if (recalled) evidenceHits++;
                for (PolicyRagService.Citation citation : answer.citations()) {
                    totalCitations++;
                    if (expected.contains(citation.documentId())) correctCitations++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("case", index + 1);
            result.put("questionHash", DocumentTextChunker.sha256(evaluationCase.question()));
            result.put("expectedDocumentIds", evaluationCase.expectedDocumentIds());
            result.put("expectRefusal", evaluationCase.expectRefusal());
            result.put("mode", answer.mode());
            result.put("recalled", recalled);
            result.put("refused", refused);
            result.put("citationDocumentIds", answer.citations().stream()
                    .map(PolicyRagService.Citation::documentId).toList());
            result.put("traceId", answer.traceId());
            caseResults.add(result);
        }

        BigDecimal recall = ratio(evidenceHits, evidenceCases);
        BigDecimal precision = ratio(correctCitations, totalCitations);
        BigDecimal refusal = ratio(correctRefusals, expectedRefusals);
        boolean passed = cases.size() >= MIN_CASES
                && evidenceCases > 0 && expectedRefusals > 0
                && recall.compareTo(MIN_EVIDENCE_RECALL) >= 0
                && precision.compareTo(MIN_CITATION_PRECISION) >= 0
                && refusal.compareTo(MIN_REFUSAL_ACCURACY) >= 0;
        Map<String, Object> thresholds = Map.of(
                "minimumCases", MIN_CASES,
                "minimumEvidenceRecall", MIN_EVIDENCE_RECALL,
                "minimumCitationPrecision", MIN_CITATION_PRECISION,
                "minimumRefusalAccuracy", MIN_REFUSAL_ACCURACY,
                "requiresEvidenceAndRefusalCases", true);
        String datasetHash = DocumentTextChunker.sha256(safeName + "\n" + String.join("\n", canonical));
        return store.save(new EvaluationDraft(
                actor.associationId(), safeName, datasetHash, cases.size(), recall, precision, refusal,
                estimatedCost, passed, thresholds, caseResults, actor.subject()));
    }

    public AiReadiness readiness(UUID associationId) {
        EvaluationRun latest = store.latest(associationId).orElse(null);
        if (latest == null) {
            return new AiReadiness(false, "ASSOCIATION_COLLABORATION_PLATFORM",
                    "no real-data RAG evaluation has been recorded for this association", null);
        }
        if (!latest.passed()) {
            return new AiReadiness(false, "ASSOCIATION_COLLABORATION_PLATFORM",
                    "the latest real-data RAG evaluation did not meet all release thresholds", latest);
        }
        return new AiReadiness(true, "AI_PLATFORM", "the latest real-data RAG evaluation passed", latest);
    }

    private EvaluationCase validateCase(EvaluationCase value, KnowledgeActor actor) {
        if (value == null || value.question() == null || value.question().isBlank()
                || value.question().length() > 2000) {
            throw new IllegalArgumentException("each evaluation question is required and must not exceed 2000 characters");
        }
        List<UUID> expected = value.expectedDocumentIds() == null
                ? List.of() : value.expectedDocumentIds().stream().distinct().toList();
        if (value.expectRefusal() && !expected.isEmpty()) {
            throw new IllegalArgumentException("refusal cases must not declare expected evidence documents");
        }
        if (!value.expectRefusal() && expected.isEmpty()) {
            throw new IllegalArgumentException("evidence cases must declare at least one expected document");
        }
        for (UUID documentId : expected) {
            var document = knowledgeService.getDocument(documentId, actor, false);
            if (!"PUBLISHED".equals(document.status())) {
                throw new IllegalArgumentException(
                        "evaluation evidence documents must be reviewed and published in the selected association");
            }
        }
        return new EvaluationCase(value.question().trim(), expected, value.expectRefusal());
    }

    private String canonical(EvaluationCase value) {
        String ids = value.expectedDocumentIds().stream().map(UUID::toString).sorted()
                .reduce((left, right) -> left + "," + right).orElse("");
        return value.expectRefusal() + "|" + value.question() + "|" + ids;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    public record EvaluationCase(String question, List<UUID> expectedDocumentIds, boolean expectRefusal) {
        public EvaluationCase {
            expectedDocumentIds = expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        }
    }

    public record AiReadiness(boolean ready, String allowedProductLabel, String reason, EvaluationRun latestRun) {}
}
