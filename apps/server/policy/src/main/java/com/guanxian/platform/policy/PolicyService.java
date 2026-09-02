package com.guanxian.platform.policy;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PolicyService {
    private static final Set<String> EDITABLE_STATES = Set.of("DRAFT", "REJECTED");
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "MEMBERS", "PARTNERS", "PUBLIC");
    private static final ActorScope PUBLIC_SCOPE = new ActorScope(
            null, "internal-dashboard", "internal-dashboard", null, null, Set.of(), Set.of());

    private final PolicyStore store;

    public PolicyService(PolicyStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<PolicyView> findAll(String query) {
        return store.list(PUBLIC_SCOPE, query, false, 0, 100);
    }

    @Transactional(readOnly = true)
    public List<PolicyView> findAll(String query, ActorScope actor) {
        return store.list(actor, query, false, 0, 100);
    }

    @Transactional(readOnly = true)
    public List<PolicyView> all() {
        return findAll(null);
    }

    @Transactional(readOnly = true)
    public PolicyPage page(ActorScope actor, String query, boolean includeDeleted, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) safePage * safeSize;
        boolean allowedDeleted = includeDeleted && (actor.isSystemAdmin() || actor.isAssociationStaff());
        return new PolicyPage(store.list(actor, query, allowedDeleted, offset, safeSize),
                store.count(actor, query, allowedDeleted), safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PolicyView get(UUID id, ActorScope actor, boolean includeDeleted) {
        boolean allowedDeleted = includeDeleted && (actor.isSystemAdmin() || actor.isAssociationStaff());
        return store.find(id, actor, allowedDeleted).orElseThrow(() -> new NotFoundException("policy", id));
    }

    @Transactional
    public PolicyView create(PolicyUpsertRequest request, ActorScope actor) {
        validate(request);
        UUID associationId = writableAssociation(request.associationId(), actor);
        PolicyView created = store.create(associationId, request, actor);
        store.recordChange(actor, "CREATE", created, null);
        return created;
    }

    @Transactional
    public PolicyView update(UUID id, long expectedVersion, PolicyUpsertRequest request, ActorScope actor) {
        validate(request);
        PolicyView current = get(id, actor, false);
        requireWriter(actor, current.associationId());
        requireState(current.status(), EDITABLE_STATES, "policy must be DRAFT or REJECTED to edit");
        requireVersion(current.version(), expectedVersion);
        PolicyView updated = store.update(id, expectedVersion, request, actor).orElseThrow(PolicyService::stale);
        store.recordChange(actor, "UPDATE", updated, null);
        return updated;
    }

    @Transactional
    public PolicyView submit(UUID id, long expectedVersion, ActorScope actor) {
        PolicyView current = get(id, actor, false);
        requireWriter(actor, current.associationId());
        requireState(current.status(), EDITABLE_STATES, "only a draft or rejected policy can be submitted");
        return transition(current, expectedVersion, "PENDING_REVIEW", "SUBMIT", null, actor);
    }

    @Transactional
    public PolicyView review(
            UUID id, long expectedVersion, PolicyReviewRequest request, ActorScope actor) {
        PolicyView current = get(id, actor, false);
        requireReviewer(actor, current.associationId());
        requireState(current.status(), Set.of("PENDING_REVIEW"), "policy is not pending review");
        String status = request.approved() ? "PUBLISHED" : "REJECTED";
        return transition(current, expectedVersion, status,
                request.approved() ? "APPROVE" : "REJECT", request.comment(), actor);
    }

    @Transactional
    public PolicyView disable(UUID id, long expectedVersion, ActorScope actor) {
        PolicyView current = get(id, actor, false);
        requireReviewer(actor, current.associationId());
        if ("DISABLED".equals(current.status())) {
            throw new PreconditionFailedException("policy is already disabled");
        }
        return transition(current, expectedVersion, "DISABLED", "DISABLE", null, actor);
    }

    @Transactional
    public PolicyView delete(UUID id, long expectedVersion, ActorScope actor) {
        PolicyView current = get(id, actor, false);
        requireReviewer(actor, current.associationId());
        requireVersion(current.version(), expectedVersion);
        PolicyView deleted = store.softDelete(id, expectedVersion, actor).orElseThrow(PolicyService::stale);
        store.recordChange(actor, "SOFT_DELETE", deleted, null);
        return deleted;
    }

    @Transactional
    public PolicyView restore(UUID id, long expectedVersion, ActorScope actor) {
        PolicyView current = get(id, actor, true);
        requireReviewer(actor, current.associationId());
        requireVersion(current.version(), expectedVersion);
        PolicyView restored;
        if (current.deleted()) {
            restored = store.restore(id, expectedVersion, actor).orElseThrow(PolicyService::stale);
        } else if ("DISABLED".equals(current.status()) || current.disabled()) {
            restored = store.transition(id, expectedVersion, "DRAFT", actor)
                    .orElseThrow(PolicyService::stale);
        } else {
            throw new PreconditionFailedException("policy is neither deleted nor disabled");
        }
        store.recordChange(actor, "RESTORE", restored, null);
        return restored;
    }

    @Transactional(readOnly = true)
    public List<PolicyHistoryView> history(UUID id, ActorScope actor, int limit) {
        PolicyView policy = get(id, actor, true);
        requireWriter(actor, policy.associationId());
        return store.history(id, actor, Math.min(Math.max(limit, 1), 100));
    }

    private PolicyView transition(PolicyView current, long expectedVersion, String status,
                                  String action, String comment, ActorScope actor) {
        requireVersion(current.version(), expectedVersion);
        PolicyView updated = store.transition(UUID.fromString(current.id()), expectedVersion, status, actor)
                .orElseThrow(PolicyService::stale);
        store.recordChange(actor, action, updated, comment);
        return updated;
    }

    private static void validate(PolicyUpsertRequest request) {
        if (request.publishDate() != null && request.effectiveDate() != null
                && request.effectiveDate().isBefore(request.publishDate())) {
            throw new PreconditionFailedException("effectiveDate must not be before publishDate");
        }
        String visibility = request.visibility() == null || request.visibility().isBlank()
                ? "MEMBERS" : request.visibility().trim().toUpperCase(Locale.ROOT);
        if (!VISIBILITIES.contains(visibility)) {
            throw new PreconditionFailedException("visibility must be PRIVATE, MEMBERS, PARTNERS or PUBLIC");
        }
        validateSourceUrl(request.sourceUrl());
    }

    private static void validateSourceUrl(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw invalidSourceUrl();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidSourceUrl();
        }
    }

    private static ApiException invalidSourceUrl() {
        return new ApiException(
                "INVALID_POLICY_SOURCE_URL",
                "sourceUrl must be an HTTP or HTTPS URL without embedded credentials",
                HttpStatus.BAD_REQUEST);
    }

    private static UUID writableAssociation(UUID requested, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            UUID selected = requireAssociationContext(actor);
            if (requested != null && !requested.equals(selected)) {
                throw scopeViolation();
            }
            return selected;
        }
        if (actor.isAssociationStaff() && actor.associationId() != null) {
            if (requested != null && !requested.equals(actor.associationId())) {
                throw scopeViolation();
            }
            return actor.associationId();
        }
        throw new ForbiddenException("POLICY_WRITE_SCOPE_REQUIRED",
                "an association staff identity is required to maintain policies");
    }

    private static void requireWriter(ActorScope actor, UUID associationId) {
        if (actor.isSystemAdmin()) {
            requireSelectedAssociation(actor, associationId);
            return;
        }
        if (!actor.isAssociationStaff() || actor.associationId() == null
                || !actor.associationId().equals(associationId)) {
            throw scopeViolation();
        }
    }

    private static void requireReviewer(ActorScope actor, UUID associationId) {
        if (actor.isSystemAdmin()) {
            requireSelectedAssociation(actor, associationId);
            return;
        }
        if (!actor.isAssociationReviewer() || actor.associationId() == null
                || !actor.associationId().equals(associationId)) {
            throw new ForbiddenException("POLICY_REVIEWER_REQUIRED",
                    "an association reviewer for this policy is required");
        }
    }

    private static void requireState(String actual, Set<String> expected, String message) {
        if (!expected.contains(actual)) {
            throw new PreconditionFailedException(message);
        }
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static PreconditionFailedException stale() {
        return new PreconditionFailedException("resource version is stale; reload and retry with the latest ETag");
    }

    private static void requireSelectedAssociation(ActorScope actor, UUID associationId) {
        UUID selected = requireAssociationContext(actor);
        if (!selected.equals(associationId)) {
            throw scopeViolation();
        }
    }

    private static UUID requireAssociationContext(ActorScope actor) {
        if (actor.associationId() == null) {
            throw new ApiException(
                    "ASSOCIATION_CONTEXT_REQUIRED",
                    "system administrators must select an association context",
                    HttpStatus.BAD_REQUEST);
        }
        return actor.associationId();
    }

    private static ForbiddenException scopeViolation() {
        return new ForbiddenException("POLICY_SCOPE_VIOLATION",
                "policies can only be maintained in the selected association context");
    }
}
