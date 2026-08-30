package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisDraft;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisSource;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ImpactActor;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ReadScope;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.SourceChunk;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
public class MemoryPolicyImpactAnalysisStore implements PolicyImpactAnalysisStore {
    private static final UUID DEMO_ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID DEMO_POLICY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DEMO_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private final Map<UUID, PolicyImpactAnalysisView> values = new LinkedHashMap<>();
    private final Map<UUID, List<PolicyImpactHistoryView>> histories = new LinkedHashMap<>();
    private final Map<SourceKey, AnalysisSource> sources = new LinkedHashMap<>();
    private final EnterpriseLifecycle enterpriseLifecycle;

    public MemoryPolicyImpactAnalysisStore() {
        this(false, enterpriseId -> true);
    }

    @Autowired
    public MemoryPolicyImpactAnalysisStore(
            @Value("${guanxian.business.seed-demo-data:${guanxian.member.seed-demo-data:false}}")
            boolean seedDemoData,
            EnterpriseLifecycle enterpriseLifecycle) {
        this.enterpriseLifecycle = enterpriseLifecycle;
        if (!seedDemoData) {
            return;
        }
        putSource(new AnalysisSource(
                DEMO_POLICY,
                "城市地下管线建设管理工作指导意见",
                DEMO_ENTERPRISE,
                "京城管网科技有限公司",
                DEMO_ASSOCIATION,
                "智慧管网 管线监测 泄漏预警 数字孪生 燃气 供热",
                List.of(
                        new SourceChunk(UUID.fromString("51000000-0000-0000-0000-000000000001"),
                                "地下管线运营单位应建立数字化巡检和风险分级制度，及时处置泄漏隐患。"),
                        new SourceChunk(UUID.fromString("51000000-0000-0000-0000-000000000002"),
                                "燃气和供热管线的数据应按标准汇交，并保存完整的监测记录。"))));
    }

    public MemoryPolicyImpactAnalysisStore(boolean seedDemoData) {
        this(seedDemoData, enterpriseId -> true);
    }

    void putSource(AnalysisSource source) {
        sources.put(new SourceKey(source.policyDocumentId(), source.enterpriseId()), source);
    }

    @Override
    public synchronized Optional<AnalysisSource> loadSource(UUID policyDocumentId, UUID enterpriseId) {
        return enterpriseLifecycle.isOperational(enterpriseId)
                ? Optional.ofNullable(sources.get(new SourceKey(policyDocumentId, enterpriseId)))
                : Optional.empty();
    }

    @Override
    public synchronized Optional<PolicyImpactAnalysisView> find(UUID id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public synchronized Optional<PolicyImpactAnalysisView> findByPair(UUID policyDocumentId, UUID enterpriseId) {
        return values.values().stream()
                .filter(value -> value.policyDocumentId().equals(policyDocumentId)
                        && value.enterpriseId().equals(enterpriseId))
                .findFirst();
    }

    @Override
    public synchronized List<PolicyImpactAnalysisView> list(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId, int offset, int limit) {
        return filtered(scope, status, policyDocumentId, enterpriseId).stream()
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long count(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId) {
        return filtered(scope, status, policyDocumentId, enterpriseId).size();
    }

    @Override
    public synchronized PolicyImpactAnalysisView create(AnalysisDraft draft) {
        requireOperational(draft.enterpriseId());
        if (findByPair(draft.policyDocumentId(), draft.enterpriseId()).isPresent()) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.CONFLICT,
                    "policy impact analysis already exists for this enterprise");
        }
        Instant now = Instant.now();
        PolicyImpactAnalysisView created = view(UUID.randomUUID(), draft, "PENDING_REVIEW",
                null, null, 0, now, now);
        values.put(created.id(), created);
        return created;
    }

    @Override
    public synchronized Optional<PolicyImpactAnalysisView> reanalyze(
            UUID id, long expectedVersion, AnalysisDraft draft) {
        PolicyImpactAnalysisView current = values.get(id);
        if (current == null || !enterpriseLifecycle.isOperational(current.enterpriseId())
                || current.version() != expectedVersion) {
            return Optional.empty();
        }
        PolicyImpactAnalysisView updated = view(id, draft, "PENDING_REVIEW", null, null,
                current.version() + 1, current.createdAt(), Instant.now());
        values.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<PolicyImpactAnalysisView> review(
            UUID id, long expectedVersion, String targetStatus, String reviewerSubject) {
        PolicyImpactAnalysisView current = values.get(id);
        if (current == null || !enterpriseLifecycle.isOperational(current.enterpriseId())
                || current.version() != expectedVersion) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        PolicyImpactAnalysisView updated = new PolicyImpactAnalysisView(
                current.id(), current.policyDocumentId(), current.policyTitle(), current.enterpriseId(),
                current.enterpriseName(), current.associationId(), current.impactLevel(), current.summary(),
                current.evidenceChunkIds(), targetStatus, null, reviewerSubject, now,
                current.version() + 1, current.createdAt(), now, null);
        values.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized void recordChange(
            ImpactActor actor, String action, PolicyImpactAnalysisView value, String comment) {
        Map<String, Object> snapshot = snapshot(value, comment);
        histories.computeIfAbsent(value.id(), ignored -> new ArrayList<>())
                .add(new PolicyImpactHistoryView(
                        value.version(), action, actor.subject(), snapshot, Instant.now()));
    }

    @Override
    public synchronized List<PolicyImpactHistoryView> history(UUID id, int limit) {
        return histories.getOrDefault(id, List.of()).stream()
                .sorted(Comparator.comparingLong(PolicyImpactHistoryView::version).reversed()
                        .thenComparing(PolicyImpactHistoryView::occurredAt, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private List<PolicyImpactAnalysisView> filtered(
            ReadScope scope, String status, UUID policyDocumentId, UUID enterpriseId) {
        return values.values().stream()
                .filter(value -> scope.systemAdmin() || scope.associationStaff()
                        || enterpriseLifecycle.isOperational(value.enterpriseId()))
                .filter(value -> canRead(scope, value))
                .filter(value -> status == null || status.equals(value.status()))
                .filter(value -> policyDocumentId == null || policyDocumentId.equals(value.policyDocumentId()))
                .filter(value -> enterpriseId == null || enterpriseId.equals(value.enterpriseId()))
                .sorted(Comparator.comparing(PolicyImpactAnalysisView::updatedAt).reversed()
                        .thenComparing(PolicyImpactAnalysisView::id))
                .toList();
    }

    private static boolean canRead(ReadScope scope, PolicyImpactAnalysisView value) {
        if (scope.systemAdmin()) {
            if (scope.enterpriseId() != null && !scope.enterpriseId().equals(value.enterpriseId())) {
                return false;
            }
            return scope.associationId() == null || scope.associationId().equals(value.associationId());
        }
        if (scope.enterpriseId() != null) {
            return scope.enterpriseId().equals(value.enterpriseId());
        }
        return scope.associationStaff() && scope.associationId() != null
                && scope.associationId().equals(value.associationId());
    }

    private void requireOperational(UUID enterpriseId) {
        if (!enterpriseLifecycle.isOperational(enterpriseId)) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.PRECONDITION_FAILED,
                    "enterprise must be active before policy impact analysis");
        }
    }

    private static PolicyImpactAnalysisView view(
            UUID id, AnalysisDraft draft, String status, String reviewer, Instant reviewedAt,
            long version, Instant createdAt, Instant updatedAt) {
        return new PolicyImpactAnalysisView(
                id, draft.policyDocumentId(), draft.policyTitle(), draft.enterpriseId(), draft.enterpriseName(),
                draft.associationId(), draft.impactLevel(), draft.summary(), draft.evidenceChunkIds(), status,
                null, reviewer, reviewedAt, version, createdAt, updatedAt, null);
    }

    private static Map<String, Object> snapshot(PolicyImpactAnalysisView value, String comment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", value.id());
        snapshot.put("policyDocumentId", value.policyDocumentId());
        snapshot.put("enterpriseId", value.enterpriseId());
        snapshot.put("impactLevel", value.impactLevel());
        snapshot.put("summary", value.summary());
        snapshot.put("evidenceChunkIds", value.evidenceChunkIds());
        snapshot.put("status", value.status());
        snapshot.put("version", value.version());
        snapshot.put("analysisMethod", value.analysisMethod());
        if (value.reviewedBySubject() != null) {
            snapshot.put("reviewedBySubject", value.reviewedBySubject());
        }
        if (comment != null && !comment.isBlank()) {
            snapshot.put("comment", comment.trim());
        }
        return Map.copyOf(snapshot);
    }

    private record SourceKey(UUID policyDocumentId, UUID enterpriseId) {
    }
}
