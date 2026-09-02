package com.guanxian.platform.ai.rag;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface RagEvaluationStore {
    EvaluationRun save(EvaluationDraft draft);
    Optional<EvaluationRun> latest(UUID associationId);

    record EvaluationDraft(
            UUID associationId, String datasetName, String datasetHash, int totalCases,
            BigDecimal evidenceRecall, BigDecimal citationPrecision, BigDecimal refusalAccuracy,
            BigDecimal estimatedCost, boolean passed, Map<String, Object> thresholds,
            List<Map<String, Object>> caseResults, String executedBySubject) {}

    record EvaluationRun(
            UUID id, UUID associationId, String datasetName, String datasetHash, int totalCases,
            BigDecimal evidenceRecall, BigDecimal citationPrecision, BigDecimal refusalAccuracy,
            BigDecimal estimatedCost, boolean passed, Map<String, Object> thresholds,
            List<Map<String, Object>> caseResults, String executedBySubject, Instant createdAt) {}
}
