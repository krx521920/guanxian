package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class CrossAssociationService {
    private static final List<String> CONSENT_RESOURCE_TYPES = List.of("PRODUCT", "SERVICE", "DEMAND", "MATCH");
    private final CrossAssociationStore store;

    CrossAssociationService(CrossAssociationStore store) {
        this.store = store;
    }

    List<CrossAssociationDtos.AccessRequestView> accessRequests(ActorScope actor) {
        requireManagementReader(actor);
        return store.accessRequests().stream().filter(item -> actor.isSystemAdmin()
                || item.applicantAssociationId().equals(actor.associationId())
                || item.targetAssociationId().equals(actor.associationId())).toList();
    }

    @Transactional
    CrossAssociationDtos.AccessRequestView createAccessRequest(
            CrossAssociationDtos.AccessRequestCreate request, ActorScope actor) {
        requireReviewer(actor);
        UUID source = ownedAssociation(actor, request.applicantAssociationId());
        requireAssociation(request.targetAssociationId());
        requireDifferent(source, request.targetAssociationId());
        if (store.relationship(source, request.targetAssociationId())
                .filter(item -> "ACTIVE".equals(item.status())).isPresent()) {
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
        Instant now = Instant.now();
        var created = store.insertAccessRequest(source, request.targetAssociationId(), clean(request.reason()), actor, now);
        store.audit(actor, source, null, "ASSOCIATION_ACCESS_REQUEST_CREATE", "ASSOCIATION_ACCESS_REQUEST",
                created.id(), created);
        return created;
    }

    @Transactional
    CrossAssociationDtos.AccessRequestView reviewAccessRequest(
            UUID id, CrossAssociationDtos.AccessRequestReview request, ActorScope actor) {
        requireReviewer(actor);
        var existing = store.accessRequest(id).orElseThrow(() -> new NotFoundException("access request", id));
        requireTargetAssociation(actor, existing.targetAssociationId());
        if (!"PENDING".equals(existing.status())) {
            throw new ConflictException("access request has already been reviewed");
        }
        Instant now = Instant.now();
        String status = request.decision() == CrossAssociationDtos.AccessDecision.APPROVE ? "APPROVED" : "REJECTED";
        if ("APPROVED".equals(status)
                && request.relationshipExpiresAt() != null
                && !request.relationshipExpiresAt().isAfter(now)) {
            throw invalid("relationshipExpiresAt must be in the future");
        }
        var reviewed = store.reviewAccessRequest(id, status, clean(request.comment()), actor, now);
        store.audit(actor, existing.targetAssociationId(), null, "ASSOCIATION_ACCESS_REQUEST_" + status,
                "ASSOCIATION_ACCESS_REQUEST", id, reviewed);
        if ("APPROVED".equals(status)) {
            var relationship = store.establishRelationship(
                    existing.applicantAssociationId(), existing.targetAssociationId(),
                    !Boolean.FALSE.equals(request.allowMemberData()), request.relationshipExpiresAt(), actor, now);
            store.audit(actor, existing.targetAssociationId(), null, "ASSOCIATION_RELATIONSHIP_ESTABLISH",
                    "ASSOCIATION_RELATIONSHIP", relationshipKey(relationship), relationship);
        }
        return reviewed;
    }

    List<CrossAssociationDtos.RelationshipView> relationships(ActorScope actor) {
        requireManagementReader(actor);
        return store.relationships().stream().filter(item -> actor.isSystemAdmin()
                || item.sourceAssociationId().equals(actor.associationId())
                || item.targetAssociationId().equals(actor.associationId())).toList();
    }

    @Transactional
    CrossAssociationDtos.RelationshipView changeRelationship(
            UUID source, UUID target, long expectedVersion,
            CrossAssociationDtos.RelationshipChange request, ActorScope actor) {
        requireReviewer(actor);
        var existing = store.relationship(source, target)
                .orElseThrow(() -> new NotFoundException("association relationship", source + ":" + target));
        requireParticipantAssociation(actor, existing.sourceAssociationId(), existing.targetAssociationId());
        Instant now = Instant.now();
        String status;
        Instant expiresAt = request.expiresAt() != null ? request.expiresAt() : existing.expiresAt();
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
                if (!actor.isSystemAdmin()
                        && (existing.suspendedByAssociationId() == null
                        || !existing.suspendedByAssociationId().equals(actor.associationId()))) {
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
                suspendedByAssociationId = actor.isSystemAdmin() ? null : actor.associationId();
                suspendedBySubject = actor.subject();
            }
            case REVOKE -> {
                if (reason == null) {
                    throw invalid("reason is required when revoking a relationship");
                }
                if ("REVOKED".equals(existing.status())) {
                    throw new ConflictException("relationship is already revoked");
                }
                status = "REVOKED";
                revokedAt = now;
            }
            case EXPIRE -> {
                if (existing.expiresAt() == null || existing.expiresAt().isAfter(now)) {
                    throw new ConflictException("relationship has not reached its expiry time");
                }
                status = "EXPIRED";
            }
            default -> throw invalid("unsupported relationship action");
        }
        var changed = store.updateRelationship(source, target, expectedVersion, status, expiresAt,
                suspendedAt, suspendedByAssociationId, suspendedBySubject, revokedAt, reason, actor, now);
        store.audit(actor, existing.sourceAssociationId(), null, "ASSOCIATION_RELATIONSHIP_" + request.action(),
                "ASSOCIATION_RELATIONSHIP", relationshipKey(changed), changed);
        return changed;
    }

    List<CrossAssociationDtos.SharePolicyView> sharePolicies(ActorScope actor) {
        requireManagementReader(actor);
        return store.sharePolicies().stream().filter(item -> actor.isSystemAdmin()
                || item.sourceAssociationId().equals(actor.associationId())
                || item.targetAssociationId().equals(actor.associationId())).toList();
    }

    @Transactional
    CrossAssociationDtos.SharePolicyView createSharePolicy(
            CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor) {
        requireReviewer(actor);
        UUID source = ownedAssociation(actor, request.sourceAssociationId());
        validateSharePolicy(source, request);
        Instant now = Instant.now();
        var created = store.insertSharePolicy(source, normalized(request, now), actor, now);
        store.audit(actor, source, null, "ASSOCIATION_SHARE_POLICY_CREATE", "ASSOCIATION_SHARE_POLICY",
                created.id(), created);
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
        validateSharePolicy(existing.sourceAssociationId(), request);
        Instant now = Instant.now();
        var changed = store.updateSharePolicy(id, expectedVersion, normalized(request, now), actor, now);
        store.audit(actor, existing.sourceAssociationId(), null, "ASSOCIATION_SHARE_POLICY_UPDATE",
                "ASSOCIATION_SHARE_POLICY", id, changed);
        return changed;
    }

    List<CrossAssociationDtos.ConsentView> consents(ActorScope actor) {
        if (actor.isSystemAdmin()) return store.consents();
        if (actor.isAssociationStaff()) {
            requireBoundAssociation(actor);
            return store.consents().stream().filter(item ->
                    item.targetAssociationId().equals(actor.associationId())
                    || store.enterpriseAssociation(item.enterpriseId())
                    .filter(actor.associationId()::equals).isPresent()).toList();
        }
        if (actor.enterpriseId() != null) {
            return store.consents().stream()
                    .filter(item -> item.enterpriseId().equals(actor.enterpriseId())).toList();
        }
        throw forbidden("cross-association consent visibility is restricted");
    }

    @Transactional
    CrossAssociationDtos.ConsentView grantConsent(CrossAssociationDtos.ConsentCreate request, ActorScope actor) {
        UUID enterpriseId = ownedEnterprise(actor, request.enterpriseId());
        UUID source = store.enterpriseAssociation(enterpriseId)
                .orElseThrow(() -> new NotFoundException("enterprise", enterpriseId));
        String resourceType = normalizeStatus(request.resourceType(), "");
        if (!CONSENT_RESOURCE_TYPES.contains(resourceType)) {
            throw invalid("resourceType must be PRODUCT, SERVICE, DEMAND, or MATCH");
        }
        if (!store.resourceOwnedByEnterprise(resourceType, request.resourceId(), enterpriseId)) {
            throw forbidden("the shared resource does not belong to the bound enterprise");
        }
        requireActiveRelationship(source, request.targetAssociationId());
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw invalid("expiresAt must be in the future");
        }
        Instant now = Instant.now();
        var normalizedRequest = new CrossAssociationDtos.ConsentCreate(
                request.enterpriseId(), request.targetAssociationId(), resourceType, request.resourceId(), request.expiresAt());
        var created = store.insertConsent(enterpriseId, normalizedRequest, actor, now);
        store.audit(actor, source, enterpriseId, "ENTERPRISE_SHARE_CONSENT_GRANT", "ENTERPRISE_SHARE_CONSENT",
                created.id(), created);
        return created;
    }

    @Transactional
    CrossAssociationDtos.ConsentView revokeConsent(UUID id, ActorScope actor) {
        var existing = store.consent(id).orElseThrow(() -> new NotFoundException("share consent", id));
        ownedEnterprise(actor, existing.enterpriseId());
        if (!"ACTIVE".equals(existing.status())) {
            throw new ConflictException("share consent is not active");
        }
        Instant now = Instant.now();
        var revoked = store.revokeConsent(id, actor, now);
        UUID source = store.enterpriseAssociation(existing.enterpriseId()).orElse(null);
        store.audit(actor, source, existing.enterpriseId(), "ENTERPRISE_SHARE_CONSENT_REVOKE",
                "ENTERPRISE_SHARE_CONSENT", id, revoked);
        return revoked;
    }

    List<CrossAssociationDtos.RecommendationView> recommendations(ActorScope actor) {
        if (actor.isSystemAdmin()) return store.recommendations();
        if (actor.isAssociationStaff()) {
            requireBoundAssociation(actor);
            return store.recommendations().stream().filter(item ->
                    item.sourceAssociationId().equals(actor.associationId())
                    || item.targetAssociationId().equals(actor.associationId())).toList();
        }
        if (actor.enterpriseId() != null) {
            return store.recommendations().stream()
                    .filter(item -> recommendationBelongsToEnterprise(item, actor.enterpriseId())).toList();
        }
        throw forbidden("cross-association recommendation visibility is restricted");
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
        store.audit(actor, source, actor.enterpriseId(), "CROSS_ASSOCIATION_RECOMMENDATION_CREATE",
                "CROSS_ASSOCIATION_RECOMMENDATION", created.id(), created);
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
        String status = request.decision() == CrossAssociationDtos.RecommendationDecision.APPROVE
                ? "APPROVED" : "REJECTED";
        Instant now = Instant.now();
        var reviewed = store.reviewRecommendation(id, expectedVersion, status, clean(request.comment()), actor, now);
        store.audit(actor, existing.targetAssociationId(), null, "CROSS_ASSOCIATION_RECOMMENDATION_" + status,
                "CROSS_ASSOCIATION_RECOMMENDATION", id, reviewed);
        return reviewed;
    }

    private void validateRecommendationResources(
            CrossAssociationDtos.RecommendationCreate request, ActorScope actor, UUID sourceAssociationId) {
        if (request.demandId() == null && request.matchId() == null) {
            throw invalid("demandId or matchId is required");
        }
        CrossAssociationStore.DemandOwnership demand = request.demandId() == null ? null
                : store.demandOwnership(request.demandId())
                .orElseThrow(() -> new NotFoundException("cooperation demand", request.demandId()));
        CrossAssociationStore.MatchOwnership match = request.matchId() == null ? null
                : store.matchOwnership(request.matchId())
                .orElseThrow(() -> new NotFoundException("ecosystem match", request.matchId()));
        if (demand != null && match != null && !demand.demandId().equals(match.demandId())) {
            throw invalid("matchId does not belong to demandId");
        }
        UUID demandAssociationId = demand != null ? demand.associationId() : match.demandAssociationId();
        if (!sourceAssociationId.equals(demandAssociationId)) {
            throw forbidden("recommendation resource does not belong to the source association");
        }
        if (actor.isEnterpriseAdmin() && !actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            boolean ownsDemand = demand == null || actor.enterpriseId().equals(demand.enterpriseId());
            boolean ownsMatch = match == null || match.belongsToEnterprise(actor.enterpriseId());
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
        if (request.visibleFields().stream().map(String::trim).anyMatch(String::isEmpty)) {
            throw invalid("visibleFields cannot contain blank values");
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

    private void requireActiveRelationship(UUID source, UUID target) {
        requireAssociation(target);
        var relationship = store.relationship(source, target)
                .orElseThrow(() -> new ForbiddenException("CROSS_ASSOCIATION_NOT_AUTHORIZED", "associations are not connected"));
        if (!"ACTIVE".equals(relationship.status())
                || relationship.revokedAt() != null
                || relationship.suspendedAt() != null
                || (relationship.expiresAt() != null && !relationship.expiresAt().isAfter(Instant.now()))) {
            throw new ForbiddenException("CROSS_ASSOCIATION_NOT_AUTHORIZED", "association relationship is not active");
        }
    }

    private UUID ownedAssociation(ActorScope actor, UUID requested) {
        if (actor.isSystemAdmin()) {
            if (requested == null) {
                throw invalid("source association is required for a system administrator");
            }
            requireAssociation(requested);
            return requested;
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
            if (requested == null) {
                throw invalid("enterpriseId is required for a system administrator");
            }
            return requested;
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
    }

    private static void requireSourceAssociation(ActorScope actor, UUID source) {
        if (!actor.isSystemAdmin() && !source.equals(actor.associationId())) {
            throw forbidden("only the source association may change this resource");
        }
    }

    private static void requireTargetAssociation(ActorScope actor, UUID target) {
        if (!actor.isSystemAdmin() && !target.equals(actor.associationId())) {
            throw forbidden("only the target association may review this resource");
        }
    }

    private static void requireParticipantAssociation(ActorScope actor, UUID source, UUID target) {
        if (!actor.isSystemAdmin() && !source.equals(actor.associationId()) && !target.equals(actor.associationId())) {
            throw forbidden("only a relationship participant may change it");
        }
    }

    private static void requireCurrent(String actual, String required) {
        if (!required.equals(actual)) {
            throw new ConflictException("relationship must currently be " + required);
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
