package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisDraft;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisSource;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.ImpactActor;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PolicyImpactAnalysisService {
    private static final Set<String> STATUSES = Set.of("PENDING_REVIEW", "APPROVED", "REJECTED");

    private final PolicyImpactAnalysisStore store;
    private final DeterministicPolicyImpactAnalyzer analyzer;
    private final EnterpriseLifecycle enterpriseLifecycle;

    @Autowired
    public PolicyImpactAnalysisService(
            PolicyImpactAnalysisStore store,
            DeterministicPolicyImpactAnalyzer analyzer,
            EnterpriseLifecycle enterpriseLifecycle) {
        this.store = store;
        this.analyzer = analyzer;
        this.enterpriseLifecycle = enterpriseLifecycle;
    }

    PolicyImpactAnalysisService(
            PolicyImpactAnalysisStore store,
            DeterministicPolicyImpactAnalyzer analyzer) {
        this(store, analyzer, enterpriseId -> true);
    }

    @Transactional
    public PolicyImpactAnalysisView create(UUID policyDocumentId, UUID enterpriseId, ImpactActor actor) {
        requireWriteContext(actor);
        requireOperational(enterpriseId);
        AnalysisSource source = source(policyDocumentId, enterpriseId);
        requireReviewerFor(actor, source.associationId(), source.enterpriseId());
        requireEvidence(source);
        if (store.findByPair(policyDocumentId, enterpriseId).isPresent()) {
            throw conflict("policy impact analysis already exists for this enterprise");
        }
        PolicyImpactAnalysisView created = store.create(analyzer.analyze(source));
        store.recordChange(actor, "CREATE", created, null);
        return created;
    }

    @Transactional
    public PolicyImpactAnalysisView reanalyze(UUID id, long expectedVersion, ImpactActor actor) {
        requireWriteContext(actor);
        PolicyImpactAnalysisView current = raw(id);
        requireOperational(current.enterpriseId());
        requireReviewerFor(actor, current.associationId(), current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        AnalysisSource source = source(current.policyDocumentId(), current.enterpriseId());
        requireEvidence(source);
        AnalysisDraft draft = analyzer.analyze(source);
        PolicyImpactAnalysisView updated = store.reanalyze(id, expectedVersion, draft)
                .orElseThrow(PolicyImpactAnalysisService::stale);
        store.recordChange(actor, "REANALYZE", updated, null);
        return updated;
    }

    @Transactional
    public PolicyImpactAnalysisView review(
            UUID id, long expectedVersion, boolean approved, String comment, ImpactActor actor) {
        requireWriteContext(actor);
        PolicyImpactAnalysisView current = raw(id);
        requireOperational(current.enterpriseId());
        requireReviewerFor(actor, current.associationId(), current.enterpriseId());
        if (!"PENDING_REVIEW".equals(current.status())) {
            throw precondition("only a pending policy impact analysis can be reviewed");
        }
        requireVersion(current.version(), expectedVersion);
        String target = approved ? "APPROVED" : "REJECTED";
        PolicyImpactAnalysisView updated = store.review(id, expectedVersion, target, actor.subject())
                .orElseThrow(PolicyImpactAnalysisService::stale);
        store.recordChange(actor, approved ? "APPROVE" : "REJECT", updated, comment);
        return updated;
    }

    @Transactional(readOnly = true)
    public PolicyImpactAnalysisView get(UUID id, ImpactActor actor) {
        PolicyImpactAnalysisView value = raw(id);
        boolean historicalAdministrator = actor.systemAdmin() || actor.associationStaff();
        if (!canRead(actor, value)
                || !historicalAdministrator && !enterpriseLifecycle.isOperational(value.enterpriseId())) {
            throw notFound(id);
        }
        return value;
    }

    @Transactional(readOnly = true)
    public PolicyImpactPage page(
            ImpactActor actor,
            String requestedStatus,
            UUID policyDocumentId,
            UUID requestedEnterpriseId,
            int page,
            int size) {
        String status = normalizeStatus(requestedStatus);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        UUID enterpriseId = visibleEnterpriseFilter(actor, requestedEnterpriseId);
        var scope = actor.readScope();
        return new PolicyImpactPage(
                store.list(scope, status, policyDocumentId, enterpriseId, safePage * safeSize, safeSize),
                store.count(scope, status, policyDocumentId, enterpriseId),
                safePage,
                safeSize);
    }

    @Transactional(readOnly = true)
    public List<PolicyImpactHistoryView> history(UUID id, ImpactActor actor, int limit) {
        get(id, actor);
        return store.history(id, Math.min(Math.max(limit, 1), 100));
    }

    private AnalysisSource source(UUID policyDocumentId, UUID enterpriseId) {
        return store.loadSource(policyDocumentId, enterpriseId)
                .orElseThrow(() -> new PolicyImpactException(
                        PolicyImpactException.Reason.NOT_FOUND,
                        "published policy and enterprise were not found in the same association"));
    }

    private static void requireEvidence(AnalysisSource source) {
        if (source.chunks().isEmpty()) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.EVIDENCE_REQUIRED,
                    "no published knowledge chunks are linked to this policy");
        }
    }

    private PolicyImpactAnalysisView raw(UUID id) {
        return store.find(id).orElseThrow(() -> notFound(id));
    }

    private void requireOperational(UUID enterpriseId) {
        if (!enterpriseLifecycle.isOperational(enterpriseId)) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.PRECONDITION_FAILED,
                    "enterprise must be active before policy impact analysis");
        }
    }

    private static void requireReviewerIdentity(ImpactActor actor) {
        if (!actor.systemAdmin() && !actor.associationReviewer()) {
            throw forbidden("an association administrator is required to analyze policy impact");
        }
    }

    private static void requireWriteContext(ImpactActor actor) {
        requireReviewerIdentity(actor);
        if (actor.systemAdmin() && actor.associationId() == null) {
            throw associationContextRequired();
        }
    }

    private static void requireReviewerFor(ImpactActor actor, UUID associationId, UUID enterpriseId) {
        if (actor.systemAdmin()) {
            if (!actor.associationId().equals(associationId)
                    || actor.enterpriseId() != null && !actor.enterpriseId().equals(enterpriseId)) {
                throw forbidden("system administrators can write only inside the selected system context");
            }
            return;
        }
        if (!actor.associationReviewer() || actor.associationId() == null
                || !actor.associationId().equals(associationId)) {
            throw forbidden("association administrators can analyze only their own enterprises");
        }
    }

    private static boolean canRead(ImpactActor actor, PolicyImpactAnalysisView value) {
        if (actor.systemAdmin()) {
            if (actor.enterpriseId() != null && !actor.enterpriseId().equals(value.enterpriseId())) {
                return false;
            }
            return actor.associationId() == null || actor.associationId().equals(value.associationId());
        }
        if (actor.enterpriseId() != null) return actor.enterpriseId().equals(value.enterpriseId());
        return actor.associationStaff() && actor.associationId() != null
                && actor.associationId().equals(value.associationId());
    }

    private static UUID visibleEnterpriseFilter(ImpactActor actor, UUID requested) {
        if (actor.enterpriseId() == null) return requested;
        if (requested != null && !actor.enterpriseId().equals(requested)) {
            throw forbidden("enterprise identities can read only their own policy impacts");
        }
        return actor.enterpriseId();
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw precondition("status must be PENDING_REVIEW, APPROVED or REJECTED");
        }
        return status;
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) throw stale();
    }

    private static PolicyImpactException notFound(UUID id) {
        return new PolicyImpactException(PolicyImpactException.Reason.NOT_FOUND,
                "policy impact analysis not found: " + id);
    }

    private static PolicyImpactException forbidden(String message) {
        return new PolicyImpactException(PolicyImpactException.Reason.FORBIDDEN, message);
    }

    private static PolicyImpactException associationContextRequired() {
        return new PolicyImpactException(
                PolicyImpactException.Reason.ASSOCIATION_CONTEXT_REQUIRED,
                "system administrators must select an association context");
    }

    private static PolicyImpactException conflict(String message) {
        return new PolicyImpactException(PolicyImpactException.Reason.CONFLICT, message);
    }

    private static PolicyImpactException precondition(String message) {
        return new PolicyImpactException(PolicyImpactException.Reason.PRECONDITION_FAILED, message);
    }

    private static PolicyImpactException stale() {
        return precondition("resource version is stale; reload and retry with the latest ETag");
    }
}
