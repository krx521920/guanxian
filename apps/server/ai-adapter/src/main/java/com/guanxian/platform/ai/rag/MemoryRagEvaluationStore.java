package com.guanxian.platform.ai.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
public class MemoryRagEvaluationStore implements RagEvaluationStore {
    private final Map<UUID, EvaluationRun> runs = new HashMap<>();

    @Override
    public synchronized EvaluationRun save(EvaluationDraft draft) {
        EvaluationRun run = new EvaluationRun(
                UUID.randomUUID(), draft.associationId(), draft.datasetName(), draft.datasetHash(),
                draft.totalCases(), draft.evidenceRecall(), draft.citationPrecision(), draft.refusalAccuracy(),
                draft.estimatedCost(), draft.passed(), Map.copyOf(draft.thresholds()),
                draft.caseResults().stream().map(Map::copyOf).toList(), draft.executedBySubject(), Instant.now());
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public synchronized Optional<EvaluationRun> latest(UUID associationId) {
        return runs.values().stream().filter(run -> associationId.equals(run.associationId()))
                .max(Comparator.comparing(EvaluationRun::createdAt));
    }
}
