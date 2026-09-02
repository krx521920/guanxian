package com.guanxian.platform.ecosystem;

import java.util.function.Supplier;

/**
 * Gives the non-production in-memory adapters rollback semantics across their
 * separate match and workflow maps. PostgreSQL uses the surrounding Spring
 * transaction instead.
 */
final class InMemoryEcosystemUnitOfWork {
    private InMemoryEcosystemUnitOfWork() {
    }

    static <T> T execute(
            EcosystemMatchStore matchStore,
            EcosystemWorkflowStore workflowStore,
            Supplier<T> operation) {
        if (!(matchStore instanceof InMemoryEcosystemMatchStore memoryMatches)
                || !(workflowStore instanceof InMemoryEcosystemWorkflowStore memoryWorkflow)) {
            return operation.get();
        }
        synchronized (memoryMatches) {
            InMemoryEcosystemMatchStore.Snapshot matchSnapshot = memoryMatches.snapshot();
            InMemoryEcosystemWorkflowStore.Snapshot workflowSnapshot = memoryWorkflow.snapshot();
            try {
                return operation.get();
            } catch (RuntimeException | Error failure) {
                memoryWorkflow.restore(workflowSnapshot);
                memoryMatches.restore(matchSnapshot);
                throw failure;
            }
        }
    }
}
