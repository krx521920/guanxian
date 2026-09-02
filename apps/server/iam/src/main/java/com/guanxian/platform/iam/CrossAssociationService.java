package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class CrossAssociationService {
    private static final List<String> CONSENT_RESOURCE_TYPES =
            List.of("MEMBER", "PRODUCT", "SERVICE", "DEMAND", "MATCH");
    private final CrossAssociationStore store;

    CrossAssociationService(CrossAssociationStore store) {
        this.store = store;
    }

    List<CrossAssociationDtos.AccessRequestView> accessRequests(ActorScope actor) {
        requireManagementReader(actor);
        return store.accessRequests().stream().filter(item -> hasGlobalRead(actor)
                || item.applicantAssociationId().equals(actor.associationId())
                || item.targetAssociationId().equals(actor.associationId())).toList();
    }

    CrossAssociationPage<CrossAssociationDtos.AccessRequestView> accessRequestsPage(
            ActorScope actor, int page, int size) {
        return page(accessRequests(actor), page, size);
    }

    @Transactional
    CrossAssociationDtos.AccessRequestView createAccessRequest(
            CrossAssociationDtos.AccessRequestCreate request, ActorScope actor) {
        requireReviewer(actor);
        UUID source = ownedAssociation(actor, request.applicantAssociationId());
        requireAssociation(request.targetAssociationId());
        requireDifferent(source, request.targetAssociationId());
        Instant now = Instant.now();
        if (store.relationship(source, request.targetAssociationId())
                .filter(item -> isEffectivelyActive(item, now)).isPresent()) {
            throw new ConflictException("associations already have an active relationship");
        }
        boolean pending = store.accessRequests().stream().anyMatch(item -> "PENDING".equals(item.status())
                && ((item.applicantAssociationId().equals(source)
                        && item.targetAssociationId().equals(request.targetAssociationId()))
                    || (item.applicantAssociationId().equals(request.targetAssociationId())
                        && item.targetAssociationId().equals(source))));
        if (pending) {
            throw new ConflictException("a pending access request already exists");
        }
        var created = store.insertAccessRequest(source, request.targetAssociationId(), clean(request.reason()), actor, now);
        store.audit(actor, source, null, "ASSOCIATION_ACCESS_REQUEST_CREATE", "ASSOCIATION_ACCESS_REQUEST",
                created.id(), created.version(), created);
        return created;
    }

    @Transactional
    CrossAssociationDtos.AccessRequestView reviewAccessRequest(
            UUID id, long expectedVersion, CrossAssociationDtos.AccessRequestReview request, ActorScope actor) {
        requireReviewer(actor);
        var existing = store.accessRequest(id).orElseThrow(() -> new NotFoundException("access request", id));
        requireTargetAssociation(actor, existing.targetAssociationId());
        requireVersion(existing.version(), expectedVersion);
        if (!"PENDING".equals(existing.status())) {
            throw new ConflictException("access request has already been reviewed");
        }
        Instant now = Instant.now();
        var previousRelationship = store.relationship(
                existing.applicantAssociationId(), existing.targetAssociationId()).orElse(null);
        String status = request.decision() == CrossAssociationDtos.AccessDecision.APPROVE ? "APPROVED" : "REJECTED";
        if ("APPROVED".equals(status)
                && request.relationshipExpiresAt() != null
                && !request.relationshipExpiresAt().isAfter(now)) {
            throw invalid("relationshipExpiresAt must be in the future");
        }
        var reviewed = store.reviewAccessRequest(id, expectedVersion, status, clean(request.comment()), actor, now);
        store.audit(actor, existing.targetAssociationId(), null, "ASSOCIATION_ACCESS_REQUEST_" + status,
                "ASSOCIATION_ACCESS_REQUEST", id, reviewed.version(), reviewed);
        if ("APPROVED".equals(status)) {
            if (previousRelationship != null) {
                invalidateRelationshipConsents(
                        previousRelationship.sourceAssociationId(), previousRelationship.targetAssociationId(),
                        actor, now, "RELATIONSHIP_RESTORE");
            }
            var relationship = store.establishRelationship(
                    existing.applicantAssociationId(), existing.targetAssociationId(),
                    Boolean.TRUE.equals(request.allowMemberData()), request.relationshipExpiresAt(), actor, now);
            String action = previousRelationship == null
                    ? "ASSOCIATION_RELATIONSHIP_ESTABLISH"
                    : "ASSOCIATION_RELATIONSHIP_RESTORE";
            auditRelationship(actor, action, relationship);
        }
        return reviewed;
    }

    @Transactional
    CrossAssociationDtos.AccessRequestView cancelAccessRequest(
            UUID id, long expectedVersion, CrossAssociationDtos.AccessRequestCancel request, ActorScope actor) {
        requireReviewer(actor);
        var existing = store.accessRequest(id)
                .orElseThrow(() -> new NotFoundException("access request", id));
        requireSourceAssociation(actor, existing.applicantAssociationId());
        requireVersion(existing.version(), expectedVersion);
        if (!"PENDING".equals(existing.status())) {
            throw new ConflictException("only a pending access request can be cancelled");
        }
        var cancelled = store.cancelAccessRequest(id, expectedVersion, clean(request.reason()), actor, Instant.now());
        store.audit(actor, existing.applicantAssociationId(), null, "ASSOCIATION_ACCESS_REQUEST_CANCEL",
                "ASSOCIATION_ACCESS_REQUEST", id, cancelled.version(), cancelled);
        return cancelled;
    }

    List<CrossAssociationDtos.RelationshipView> relationships(ActorScope actor) {
        requireManagementReader(actor);
        Instant now = Instant.now();
        return store.relationships().stream().filter(item -> hasGlobalRead(actor)
                || item.sourceAssociationId().equals(actor.associationId())
                || item.targetAssociationId().equals(actor.associationId()))
                .map(item -> effectiveRelationship(item, now)).toList();
    }

    CrossAssociationPage<CrossAssociationDtos.RelationshipView> relationshipsPage(
            ActorScope actor, int page, int size) {
        return page(relationships(actor), page, size);
    }

    @Transactional
    CrossAssociationDtos.RelationshipView changeRelationship(
            UUID source, UUID target, long expectedVersion,
            CrossAssociationDtos.RelationshipChange request, ActorScope actor) {
        requireReviewer(actor);
        var persisted = store.relationship(source, target)
                .orElseThrow(() -> new NotFoundException("association relationship", source + ":" + target));
        Instant now = Instant.now();
        var existing = effectiveRelationship(persisted, now);
        requireParticipantAssociation(actor, existing.sourceAssociationId(), existing.targetAssociationId());
        if (request.expiresAt() != null && !request.expiresAt().equals(persisted.expiresAt())) {
            throw invalid("relationship expiry can only change through a newly approved access request");
        }
        String status;
        Instant expiresAt = persisted.expiresAt();
        Instant suspendedAt = existing.suspendedAt();
        UUID suspendedByAssociationId = existing.suspendedByAssociationId();
        String suspendedBySubject = existing.suspendedBySubject();
        Instant revokedAt = existing.revokedAt();
        String reason = clean(request.reason());
        switch (request.action()) {
            case ACTIVATE -> {
                if ("EXPIRED".equals(existing.status())) {
                    throw new ConflictException("an expired relationship requires a new approved access request");
                }
                requireCurrent(existing.status(), "SUSPENDED");
                if ((existing.suspendedByAssociationId() == null && !actor.isSystemAdmin())
                        || (existing.suspendedByAssociationId() != null
                        && !existing.suspendedByAssociationId().equals(actor.associationId()))) {
                    throw forbidden("only the association that suspended the relationship may reactivate it");
                }
                if (expiresAt != null && !expiresAt.isAfter(now)) {
                    throw invalid("expiresAt must be in the future when activating a relationship");
                }
                status = "ACTIVE";
                suspendedAt = null;
                suspendedByAssociationId = null;
                suspendedBySubject = null;
            }
            case SUSPEND -> {
                requireCurrent(existing.status(), "ACTIVE");
                status = "SUSPENDED";
                suspendedAt = now;
                suspendedByAssociationId = actor.associationId();
                suspendedBySubject = actor.subject();
            }
            case REVOKE -> {
                if (reason == null) {
                    throw invalid("reason is required when revoking a relationship");
                }
                requireCurrent(existing.status(), "ACTIVE", "SUSPENDED");
                status = "REVOKED";
                suspendedAt = null;
                suspendedByAssociationId = null;
                suspendedBySubject = null;
                revokedAt = now;
            }
            case EXPIRE -> {
                requireCurrent(persisted.status(), "ACTIVE", "SUSPENDED");
                if (existing.expiresAt() == null || existing.expiresAt().isAfter(now)) {
                    throw new ConflictException("relationship has not reached its expiry time");
                }
                status = "EXPIRED";
                suspendedAt = null;
                suspendedByAssociationId = null;
                suspendedBySubject = null;
                revokedAt = null;
            }
            default -> throw invalid("unsupported relationship action");
        }
        var changed = store.updateRelationship(source, target, expectedVersion, status, expiresAt,
                suspendedAt, suspendedByAssociationId, suspendedBySubject, revokedAt, reason, actor, now);
        if (request.action() == CrossAssociationDtos.RelationshipAction.REVOKE
                || request.action() == CrossAssociationDtos.RelationshipAction.EXPIRE) {
            invalidateRelationshipConsents(
                    changed.sourceAssociationId(), changed.targetAssociationId(), actor, now,
                    "RELATIONSHIP_" + request.action());
        }
        auditRelationship(actor, "ASSOCIATION_RELATIONSHIP_" + request.action(), changed);
        return changed;
    }

    List<CrossAssociationDtos.SharePolicyView> sharePolicies(ActorScope actor) {
        requireManagementReader(actor);
        return store.sharePolicies().stream().filter(item -> hasGlobalRead(actor)
                || item.sourceAssociationId().equals(actor.associationId())
                || item.targetAssociationId().equals(actor.associationId())).toList();
    }

    CrossAssociationPage<CrossAssociationDtos.SharePolicyView> sharePoliciesPage(
            ActorScope actor, int page, int size) {
        return page(sharePolicies(actor), page, size);
    }

    @Transactional
    CrossAssociationDtos.SharePolicyView createSharePolicy(
            CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor) {
        requireReviewer(actor);
        UUID source = ownedAssociation(actor, request.sourceAssociationId());
        validateSharePolicy(source, request);
        Instant now = Instant.now();
        String resourceType = request.resourceType().trim().toUpperCase(Locale.ROOT);
        boolean duplicate = store.sharePolicies().stream().anyMatch(item ->
                item.sourceAssociationId().equals(source)
                        && item.targetAssociationId().equals(request.targetAssociationId())
                        && item.resourceType().equals(resourceType));
        if (duplicate) {
            throw new ConflictException("a share policy already exists for this association pair and resource type");
        }
        var created = store.insertSharePolicy(source, normalized(request, now), actor, now);
        store.audit(actor, source, null, "ASSOCIATION_SHARE_POLICY_CREATE", "ASSOCIATION_SHARE_POLICY",
                created.id(), created.version(), created);
        return created;
    }

    @Transactional
    CrossAssociationDtos.SharePolicyView updateSharePolicy(
            UUID id, long expectedVersion, CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor) {
        requireReviewer(actor);
        var existing = store.sharePolicy(id).orElseThrow(() -> new NotFoundException("share policy", id));
        requireSourceAssociation(actor, existing.sourceAssociationId());
        if (request.sourceAssociationId() != null && !request.sourceAssociationId().equals(existing.sourceAssociationId())) {
            throw invalid("sourceAssociationId cannot be changed");
        }
        if (!request.targetAssociationId().equals(existing.targetAssociationId())) {
            throw invalid("targetAssociationId cannot be changed; create a new share policy");
        }
        if (!request.resourceType().trim().equalsIgnoreCase(existing.resourceType())) {
            throw invalid("resourceType cannot be changed; create a new share policy");
        }
        validateSharePolicy(existing.sourceAssociationId(), request);
        Instant now = Instant.now();
        var changed = store.updateSharePolicy(id, expectedVersion, normalized(request, now), actor, now);
        store.audit(actor, existing.sourceAssociationId(), null, "ASSOCIATION_SHARE_POLICY_UPDATE",
                "ASSOCIATION_SHARE_POLICY", id, changed.version(), changed);
        return changed;
    }

    @Transactional
    CrossAssociationDtos.SharePolicyView changeSharePolicyStatus(
            UUID id,
            long expectedVersion,
            CrossAssociationDtos.SharePolicyStatusChange request,
            ActorScope actor) {
        requireReviewer(actor);
        var existing = store.sharePolicy(id).orElseThrow(() -> new NotFoundException("share policy", id));
        requireSourceAssociation(actor, existing.sourceAssociationId());
        String status = request.status().name();
        if (status.equals(existing.status())) {
            throw new ConflictException("share policy is already " + status);
        }
        if ("ACTIVE".equals(status)) {
            requireActiveRelationship(existing.sourceAssociationId(), existing.targetAssociationId());
            if (existing.expiresAt() != null && !existing.expiresAt().isAfter(Instant.now())) {
                throw new ConflictException("an expired share policy must be updated before it can be activated");
            }
        }
        Instant now = Instant.now();
        var update = new CrossAssociationDtos.SharePolicyUpsert(
                existing.sourceAssociationId(), existing.targetAssociationId(), existing.resourceType(),
                existing.visibleFields(), existing.validFrom(), existing.expiresAt(), status);
        var changed = store.updateSharePolicy(id, expectedVersion, update, actor, now);
        store.audit(actor, existing.sourceAssociationId(), null, "ASSOCIATION_SHARE_POLICY_" + status,
                "ASSOCIATION_SHARE_POLICY", id, changed.version(), changed);
        return changed;
    }

    List<CrossAssociationDtos.ConsentView> consents(ActorScope actor) {
        if (hasGlobalRead(actor)) return store.consents();
        if (actor.enterpriseId() != null) {
            return store.consents().stream()
                    .filter(item -> item.enterpriseId().equals(actor.enterpriseId())).toList();
        }
        if (actor.isSystemAdmin() || actor.isAssociationStaff()) {
            requireBoundAssociation(actor);
            return store.consents().stream().filter(item ->
                    item.targetAssociationId().equals(actor.associationId())
                    || store.enterpriseAssociation(item.enterpriseId())
                    .filter(actor.associationId()::equals).isPresent()).toList();
        }
        throw forbidden("cross-association consent visibility is restricted");
    }

    CrossAssociationPage<CrossAssociationDtos.ConsentView> consentsPage(
            ActorScope actor, int page, int size) {
        return page(consents(actor), page, size);
    }

    List<CrossAssociationDtos.ConsentTargetView> consentTargets(ActorScope actor) {
        UUID enterpriseId = ownedEnterprise(actor, null);
        UUID source = store.enterpriseAssociation(enterpriseId)
                .orElseThrow(() -> new NotFoundException("enterprise", enterpriseId));
        Instant now = Instant.now();
        Map<UUID, Instant> activeTargets = new java.util.LinkedHashMap<>();
        store.relationships().stream()
                .filter(item -> isEffectivelyActive(item, now) && item.allowMemberData())
                .filter(item -> item.sourceAssociationId().equals(source)
                        || item.targetAssociationId().equals(source))
                .forEach(item -> activeTargets.put(
                        item.sourceAssociationId().equals(source)
                                ? item.targetAssociationId() : item.sourceAssociationId(),
                        item.expiresAt()));
        Map<String, CrossAssociationDtos.ConsentTargetView> targets = store.sharePolicies().stream()
                .filter(item -> item.sourceAssociationId().equals(source))
                .filter(item -> activeTargets.containsKey(item.targetAssociationId()))
                .filter(item -> "ACTIVE".equals(item.status()))
                .filter(item -> !item.validFrom().isAfter(now))
                .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(now))
                .filter(CrossAssociationService::isExecutablePolicy)
                .map(item -> new CrossAssociationDtos.ConsentTargetView(
                        item.targetAssociationId(), item.resourceType(), earliestFiniteExpiry(
                        item.expiresAt(), activeTargets.get(item.targetAssociationId()))))
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.targetAssociationId() + "|" + item.resourceType(),
                        item -> item,
                        CrossAssociationService::mostRestrictiveTarget,
                        java.util.LinkedHashMap::new));
        return targets.values().stream()
                .sorted(java.util.Comparator.comparing(CrossAssociationDtos.ConsentTargetView::targetAssociationId)
                        .thenComparing(CrossAssociationDtos.ConsentTargetView::resourceType))
                .toList();
    }

    @Transactional
    CrossAssociationDtos.ConsentView grantConsent(CrossAssociationDtos.ConsentCreate request, ActorScope actor) {
        UUID enterpriseId = ownedEnterprise(actor, request.enterpriseId());
        UUID source = store.enterpriseAssociation(enterpriseId)
                .orElseThrow(() -> new NotFoundException("enterprise", enterpriseId));
        String resourceType = normalizeStatus(request.resourceType(), "");
        if (!CONSENT_RESOURCE_TYPES.contains(resourceType)) {
            throw invalid("resourceType must be MEMBER, PRODUCT, SERVICE, DEMAND, or MATCH");
        }
        if (!store.resourceOwnedByEnterprise(resourceType, request.resourceId(), enterpriseId)) {
            throw forbidden("the shared resource does not belong to the bound enterprise");
        }
        var relationship = requireActiveRelationship(source, request.targetAssociationId());
        Instant now = Instant.now();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw invalid("expiresAt must be in the future");
        }
        List<CrossAssociationDtos.SharePolicyView> policies = effectivePolicies(
                source, request.targetAssociationId(), resourceType, now);
        if (policies.isEmpty()) {
            throw new ForbiddenException(
                    "CROSS_ASSOCIATION_NOT_AUTHORIZED", "no active share policy authorizes this resource type");
        }
        Instant policyExpiry = policies.stream().map(CrossAssociationDtos.SharePolicyView::expiresAt)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant authorizationExpiry = earliestFiniteExpiry(policyExpiry, relationship.expiresAt());
        if (authorizationExpiry != null
                && (request.expiresAt() == null || request.expiresAt().isAfter(authorizationExpiry))) {
            throw invalid("consent expiresAt must not be later than the relationship and share-policy authorization expiry");
        }
        for (var expired : store.materializeExpiredConsents(
                enterpriseId, request.targetAssociationId(), resourceType, request.resourceId(), now)) {
            store.audit(actor, source, enterpriseId, "ENTERPRISE_SHARE_CONSENT_EXPIRE",
                    "ENTERPRISE_SHARE_CONSENT", expired.id(), expired);
        }
        boolean duplicate = store.consents().stream().anyMatch(item ->
                item.enterpriseId().equals(enterpriseId)
                        && item.targetAssociationId().equals(request.targetAssociationId())
                        && item.resourceType().equals(resourceType)
                        && item.resourceId().equals(request.resourceId())
                        && "ACTIVE".equals(item.status())
                        && item.revokedAt() == null
                        && (item.expiresAt() == null || item.expiresAt().isAfter(now)));
        if (duplicate) {
            throw new ConflictException("an active consent already exists for this resource and target association");
        }
        var normalizedRequest = new CrossAssociationDtos.ConsentCreate(
                enterpriseId, request.targetAssociationId(), resourceType, request.resourceId(), request.expiresAt());
        var created = store.insertConsent(enterpriseId, normalizedRequest, actor, now);
        store.audit(actor, source, enterpriseId, "ENTERPRISE_SHARE_CONSENT_GRANT", "ENTERPRISE_SHARE_CONSENT",
                created.id(), created.version(), created);
        return created;
    }

    @Transactional
    CrossAssociationDtos.ConsentView revokeConsent(UUID id, long expectedVersion, ActorScope actor) {
        var existing = store.consent(id).orElseThrow(() -> new NotFoundException("share consent", id));
        ownedEnterprise(actor, existing.enterpriseId());
        requireVersion(existing.version(), expectedVersion);
        if (!"ACTIVE".equals(existing.status())) {
            throw new ConflictException("share consent is not active");
        }
        Instant now = Instant.now();
        var revoked = store.revokeConsent(id, expectedVersion, actor, now);
        UUID source = store.enterpriseAssociation(existing.enterpriseId()).orElse(null);
        store.audit(actor, source, existing.enterpriseId(), "ENTERPRISE_SHARE_CONSENT_REVOKE",
                "ENTERPRISE_SHARE_CONSENT", id, revoked.version(), revoked);
        return revoked;
    }

    List<CrossAssociationDtos.RecommendationView> recommendations(ActorScope actor) {
        if (hasGlobalRead(actor)) return store.recommendations();
        if (actor.enterpriseId() != null) {
            return store.recommendations().stream()
                    .filter(item -> recommendationBelongsToEnterprise(item, actor.enterpriseId())).toList();
        }
        if (actor.isSystemAdmin() || actor.isAssociationStaff()) {
            requireBoundAssociation(actor);
            return store.recommendations().stream().filter(item ->
                    item.sourceAssociationId().equals(actor.associationId())
                    || item.targetAssociationId().equals(actor.associationId())).toList();
        }
        throw forbidden("cross-association recommendation visibility is restricted");
    }

    CrossAssociationPage<CrossAssociationDtos.RecommendationView> recommendationsPage(
            ActorScope actor, int page, int size) {
        return page(recommendations(actor), page, size);
    }

    private static <T> CrossAssociationPage<T> page(List<T> values, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = Math.min(safePage * safeSize, values.size());
        int to = Math.min(from + safeSize, values.size());
        return new CrossAssociationPage<>(List.copyOf(values.subList(from, to)), values.size(), safePage, safeSize);
    }

    @Transactional
    CrossAssociationDtos.RecommendationView createRecommendation(
            CrossAssociationDtos.RecommendationCreate request, ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer() && !actor.isEnterpriseAdmin()) {
            throw forbidden("only an association reviewer or enterprise administrator may create a recommendation");
        }
        UUID source = ownedAssociation(actor, request.sourceAssociationId());
        requireActiveRelationship(source, request.targetAssociationId());
        validateRecommendationResources(request, actor, source);
        Instant now = Instant.now();
        var created = store.insertRecommendation(source, request, actor, now);
        auditRecommendation(actor, "CROSS_ASSOCIATION_RECOMMENDATION_CREATE", created);
        return created;
    }

    @Transactional
    CrossAssociationDtos.RecommendationView reviewRecommendation(
            UUID id, long expectedVersion, CrossAssociationDtos.RecommendationReview request, ActorScope actor) {
        requireReviewer(actor);
        var existing = store.recommendation(id)
                .orElseThrow(() -> new NotFoundException("cross-association recommendation", id));
        requireTargetAssociation(actor, existing.targetAssociationId());
        requireActiveRelationship(existing.sourceAssociationId(), existing.targetAssociationId());
        if (!"PENDING_REVIEW".equals(existing.status())) {
            throw new ConflictException("recommendation has already been reviewed");
        }
        if (request.decision() == CrossAssociationDtos.RecommendationDecision.APPROVE) {
            validateRecommendationResources(
                    existing.demandId(), existing.matchId(), existing.sourceAssociationId(), null);
        }
        String status = request.decision() == CrossAssociationDtos.RecommendationDecision.APPROVE
                ? "APPROVED" : "REJECTED";
        Instant now = Instant.now();
        var reviewed = store.reviewRecommendation(id, expectedVersion, status, clean(request.comment()), actor, now);
        auditRecommendation(actor, "CROSS_ASSOCIATION_RECOMMENDATION_" + status, reviewed);
        return reviewed;
    }

    private void validateRecommendationResources(
            CrossAssociationDtos.RecommendationCreate request, ActorScope actor, UUID sourceAssociationId) {
        validateRecommendationResources(
                request.demandId(), request.matchId(), sourceAssociationId, actor.enterpriseId());
    }

    private void validateRecommendationResources(
            UUID demandId, UUID matchId, UUID sourceAssociationId, UUID actorEnterpriseId) {
        if (demandId == null && matchId == null) {
            throw invalid("demandId or matchId is required");
        }
        CrossAssociationStore.DemandOwnership demand = demandId == null ? null
                : store.demandOwnership(demandId)
                .orElseThrow(() -> new NotFoundException("cooperation demand", demandId));
        CrossAssociationStore.MatchOwnership match = matchId == null ? null
                : store.matchOwnership(matchId)
                .orElseThrow(() -> new NotFoundException("ecosystem match", matchId));
        if (demand != null && match != null && !demand.demandId().equals(match.demandId())) {
            throw invalid("matchId does not belong to demandId");
        }
        if (demand != null && !sourceAssociationId.equals(demand.associationId())) {
            throw forbidden("recommendation demand does not belong to the source association");
        }
        if (match != null
                && !sourceAssociationId.equals(match.demandAssociationId())
                && !sourceAssociationId.equals(match.candidateAssociationId())) {
            throw forbidden("recommendation resource does not belong to the source association");
        }
        if (actorEnterpriseId != null) {
            boolean ownsDemand = demand == null || actorEnterpriseId.equals(demand.enterpriseId());
            boolean ownsMatch = match == null || match.belongsToEnterprise(actorEnterpriseId);
            if (!ownsDemand || !ownsMatch) {
                throw forbidden("enterprise administrators may recommend only their own resources");
            }
        }
    }

    private boolean recommendationBelongsToEnterprise(
            CrossAssociationDtos.RecommendationView recommendation, UUID enterpriseId) {
        boolean ownsDemand = recommendation.demandId() != null
                && store.demandOwnership(recommendation.demandId())
                .filter(value -> enterpriseId.equals(value.enterpriseId())).isPresent();
        boolean ownsMatch = recommendation.matchId() != null
                && store.matchOwnership(recommendation.matchId())
                .filter(value -> value.belongsToEnterprise(enterpriseId)).isPresent();
        return ownsDemand || ownsMatch;
    }

    private void validateSharePolicy(UUID source, CrossAssociationDtos.SharePolicyUpsert request) {
        requireActiveRelationship(source, request.targetAssociationId());
        String resourceType = request.resourceType().trim().toUpperCase(Locale.ROOT);
        if (!CONSENT_RESOURCE_TYPES.contains(resourceType)) {
            throw invalid("resourceType must be MEMBER, PRODUCT, SERVICE, DEMAND, or MATCH");
        }
        if (request.visibleFields().isEmpty()) {
            throw invalid("visibleFields must contain at least one authorized field");
        }
        if (request.visibleFields().stream().map(String::trim).anyMatch(String::isEmpty)) {
            throw invalid("visibleFields cannot contain blank values");
        }
        Set<String> fields = request.visibleFields().stream().map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> allowed = PartnerFieldAuthorization.allowedFields(resourceType);
        if (!allowed.containsAll(fields)) {
            throw invalid("visibleFields contains unsupported fields for " + resourceType);
        }
        Set<String> required = PartnerFieldAuthorization.requiredFields(resourceType);
        if (!fields.containsAll(required)) {
            throw invalid("visibleFields must include " + String.join(", ", required));
        }
        Instant from = request.validFrom() == null ? Instant.now() : request.validFrom();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(from)) {
            throw invalid("expiresAt must be later than validFrom");
        }
        String status = normalizeStatus(request.status(), "ACTIVE");
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw invalid("share policy status must be ACTIVE or SUSPENDED");
        }
    }

    private static CrossAssociationDtos.SharePolicyUpsert normalized(
            CrossAssociationDtos.SharePolicyUpsert request, Instant now) {
        return new CrossAssociationDtos.SharePolicyUpsert(request.sourceAssociationId(), request.targetAssociationId(),
                request.resourceType().trim().toUpperCase(Locale.ROOT),
                request.visibleFields().stream().map(String::trim).distinct().sorted().toList(),
                request.validFrom() == null ? now : request.validFrom(), request.expiresAt(),
                normalizeStatus(request.status(), "ACTIVE"));
    }

    private CrossAssociationDtos.RelationshipView requireActiveRelationship(UUID source, UUID target) {
        requireAssociation(target);
        var relationship = store.relationship(source, target)
                .orElseThrow(() -> new ForbiddenException("CROSS_ASSOCIATION_NOT_AUTHORIZED", "associations are not connected"));
        if (!"ACTIVE".equals(relationship.status())
                || !relationship.allowMemberData()
                || relationship.revokedAt() != null
                || relationship.suspendedAt() != null
                || (relationship.expiresAt() != null && !relationship.expiresAt().isAfter(Instant.now()))) {
            throw new ForbiddenException("CROSS_ASSOCIATION_NOT_AUTHORIZED", "association relationship is not active");
        }
        return relationship;
    }

    private UUID ownedAssociation(ActorScope actor, UUID requested) {
        if (actor.isSystemAdmin()) {
            UUID selected = requireWriteAssociation(actor);
            if (requested != null && !requested.equals(selected)) {
                throw forbidden("cannot act outside the selected association context");
            }
            requireAssociation(selected);
            return selected;
        }
        if (actor.associationId() == null) {
            throw forbidden("identity is not bound to an association");
        }
        if (requested != null && !requested.equals(actor.associationId())) {
            throw forbidden("cannot act for another association");
        }
        return actor.associationId();
    }

    private UUID ownedEnterprise(ActorScope actor, UUID requested) {
        if (actor.isSystemAdmin()) {
            UUID selectedAssociation = requireWriteAssociation(actor);
            UUID selectedEnterprise = actor.enterpriseId();
            if (selectedEnterprise == null) {
                throw forbidden("system administrators must select an enterprise for enterprise-level writes");
            }
            if (requested != null && !requested.equals(selectedEnterprise)) {
                throw forbidden("cannot act outside the selected enterprise context");
            }
            UUID enterpriseAssociation = store.enterpriseAssociation(selectedEnterprise)
                    .orElseThrow(() -> new NotFoundException("enterprise", selectedEnterprise));
            if (!selectedAssociation.equals(enterpriseAssociation)) {
                throw forbidden("selected enterprise does not belong to the selected association");
            }
            return selectedEnterprise;
        }
        if (!actor.isEnterpriseAdmin() || actor.enterpriseId() == null) {
            throw forbidden("only the bound enterprise administrator may manage consent");
        }
        if (requested != null && !requested.equals(actor.enterpriseId())) {
            throw forbidden("cannot manage another enterprise's consent");
        }
        return actor.enterpriseId();
    }

    private void requireAssociation(UUID id) {
        if (!store.associationExists(id)) {
            throw new NotFoundException("association", id);
        }
    }

    private static void requireDifferent(UUID source, UUID target) {
        if (source.equals(target)) {
            throw invalid("source and target associations must be different");
        }
    }

    private static void requireManagementReader(ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationStaff()) {
            throw forbidden("cross-association management visibility is restricted to association staff");
        }
        requireBoundAssociation(actor);
    }

    private static void requireBoundAssociation(ActorScope actor) {
        if (!actor.isSystemAdmin() && actor.associationId() == null) {
            throw forbidden("identity is not bound to an association");
        }
    }

    private static void requireReviewer(ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            throw forbidden("association reviewer permission is required");
        }
        requireWriteAssociation(actor);
    }

    private static void requireSourceAssociation(ActorScope actor, UUID source) {
        requireWriteAssociation(actor);
        if (!source.equals(actor.associationId())) {
            throw forbidden("only the source association may change this resource");
        }
    }

    private static void requireTargetAssociation(ActorScope actor, UUID target) {
        requireWriteAssociation(actor);
        if (!target.equals(actor.associationId())) {
            throw forbidden("only the target association may review this resource");
        }
    }

    private static void requireParticipantAssociation(ActorScope actor, UUID source, UUID target) {
        requireWriteAssociation(actor);
        if (!source.equals(actor.associationId()) && !target.equals(actor.associationId())) {
            throw forbidden("only a relationship participant may change it");
        }
    }

    private static boolean hasGlobalRead(ActorScope actor) {
        return actor.isSystemAdmin() && actor.associationId() == null;
    }

    private static boolean isEffectivelyActive(
            CrossAssociationDtos.RelationshipView relationship, Instant now) {
        return "ACTIVE".equals(relationship.status())
                && relationship.suspendedAt() == null
                && relationship.revokedAt() == null
                && (relationship.expiresAt() == null || relationship.expiresAt().isAfter(now));
    }

    private static CrossAssociationDtos.RelationshipView effectiveRelationship(
            CrossAssociationDtos.RelationshipView relationship, Instant now) {
        if (!("ACTIVE".equals(relationship.status()) || "SUSPENDED".equals(relationship.status()))
                || relationship.expiresAt() == null
                || relationship.expiresAt().isAfter(now)) {
            return relationship;
        }
        return new CrossAssociationDtos.RelationshipView(
                relationship.sourceAssociationId(), relationship.targetAssociationId(), "EXPIRED",
                relationship.allowMemberData(), relationship.expiresAt(), null,
                null, null, null, null, null,
                relationship.version(), relationship.createdAt(), relationship.updatedAt());
    }

    private List<CrossAssociationDtos.SharePolicyView> effectivePolicies(
            UUID source, UUID target, String resourceType, Instant now) {
        return store.sharePolicies().stream()
                .filter(item -> item.sourceAssociationId().equals(source))
                .filter(item -> item.targetAssociationId().equals(target))
                .filter(item -> item.resourceType().equals(resourceType))
                .filter(item -> "ACTIVE".equals(item.status()))
                .filter(item -> !item.validFrom().isAfter(now))
                .filter(item -> item.expiresAt() == null || item.expiresAt().isAfter(now))
                .filter(CrossAssociationService::isExecutablePolicy)
                .toList();
    }

    private static boolean isExecutablePolicy(CrossAssociationDtos.SharePolicyView policy) {
        Set<String> allowed = PartnerFieldAuthorization.allowedFields(policy.resourceType());
        if (policy.visibleFields() == null || policy.visibleFields().stream().anyMatch(java.util.Objects::isNull)) {
            return false;
        }
        Set<String> fields = new java.util.LinkedHashSet<>(policy.visibleFields());
        return !fields.isEmpty()
                && allowed.containsAll(fields)
                && fields.containsAll(PartnerFieldAuthorization.requiredFields(policy.resourceType()));
    }

    static CrossAssociationDtos.ConsentTargetView mostRestrictiveTarget(
            CrossAssociationDtos.ConsentTargetView left,
            CrossAssociationDtos.ConsentTargetView right) {
        Instant leftExpiry = left.policyExpiresAt();
        Instant rightExpiry = right.policyExpiresAt();
        Instant expiry;
        if (leftExpiry == null) expiry = rightExpiry;
        else if (rightExpiry == null) expiry = leftExpiry;
        else expiry = leftExpiry.isBefore(rightExpiry) ? leftExpiry : rightExpiry;
        return new CrossAssociationDtos.ConsentTargetView(
                left.targetAssociationId(), left.resourceType(), expiry);
    }

    private static Instant earliestFiniteExpiry(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private void invalidateRelationshipConsents(
            UUID source, UUID target, ActorScope actor, Instant now, String cause) {
        for (var revoked : store.revokeActiveConsentsBetweenAssociations(source, target, now)) {
            UUID ownerAssociation = store.enterpriseAssociation(revoked.enterpriseId()).orElse(source);
            store.audit(actor, ownerAssociation, revoked.enterpriseId(),
                    "ENTERPRISE_SHARE_CONSENT_INVALIDATE_" + cause,
                    "ENTERPRISE_SHARE_CONSENT", revoked.id(), revoked);
        }
    }

    private void auditRelationship(
            ActorScope actor, String action, CrossAssociationDtos.RelationshipView relationship) {
        String resourceId = relationshipKey(relationship);
        store.audit(actor, relationship.sourceAssociationId(), null, action,
                "ASSOCIATION_RELATIONSHIP", resourceId, relationship.version(), relationship);
        store.audit(actor, relationship.targetAssociationId(), null, action,
                "ASSOCIATION_RELATIONSHIP", resourceId, relationship.version(), relationship);
    }

    private void auditRecommendation(
            ActorScope actor, String action, CrossAssociationDtos.RecommendationView recommendation) {
        UUID sourceEnterpriseId = recommendation.sourceAssociationId().equals(actor.associationId())
                ? actor.enterpriseId() : null;
        store.audit(actor, recommendation.sourceAssociationId(), sourceEnterpriseId, action,
                "CROSS_ASSOCIATION_RECOMMENDATION", recommendation.id(),
                recommendation.version(), recommendation);
        if (!recommendation.targetAssociationId().equals(recommendation.sourceAssociationId())) {
            UUID targetEnterpriseId = recommendation.targetAssociationId().equals(actor.associationId())
                    ? actor.enterpriseId() : null;
            store.audit(actor, recommendation.targetAssociationId(), targetEnterpriseId, action,
                    "CROSS_ASSOCIATION_RECOMMENDATION", recommendation.id(),
                    recommendation.version(), recommendation);
        }
    }

    private static UUID requireWriteAssociation(ActorScope actor) {
        if (actor.associationId() == null) {
            throw forbidden("an association context is required for this write operation");
        }
        return actor.associationId();
    }

    private static void requireCurrent(String actual, String required) {
        if (!required.equals(actual)) {
            throw new ConflictException("relationship must currently be " + required);
        }
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new PreconditionFailedException(
                    "resource version is stale; reload and retry with the latest ETag");
        }
    }

    private static void requireCurrent(String actual, String first, String second) {
        if (!first.equals(actual) && !second.equals(actual)) {
            throw new ConflictException("relationship must currently be " + first + " or " + second);
        }
    }

    private static String relationshipKey(CrossAssociationDtos.RelationshipView relationship) {
        return relationship.sourceAssociationId() + ":" + relationship.targetAssociationId();
    }

    private static String normalizeStatus(String status, String fallback) {
        return status == null || status.isBlank() ? fallback : status.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException("INVALID_CROSS_ASSOCIATION_REQUEST", message, HttpStatus.BAD_REQUEST);
    }

    private static ForbiddenException forbidden(String message) {
        return new ForbiddenException("CROSS_ASSOCIATION_SCOPE_DENIED", message);
    }
}
