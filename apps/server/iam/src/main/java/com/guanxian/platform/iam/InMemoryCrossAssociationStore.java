package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryCrossAssociationStore implements CrossAssociationStore {
    private final Map<UUID, CrossAssociationDtos.AccessRequestView> accessRequests = new LinkedHashMap<>();
    private final Map<String, CrossAssociationDtos.RelationshipView> relationships = new LinkedHashMap<>();
    private final Map<UUID, CrossAssociationDtos.SharePolicyView> policies = new LinkedHashMap<>();
    private final Map<UUID, CrossAssociationDtos.ConsentView> consents = new LinkedHashMap<>();
    private final Map<UUID, CrossAssociationDtos.RecommendationView> recommendations = new LinkedHashMap<>();
    private final Map<UUID, UUID> enterpriseAssociations = new LinkedHashMap<>();
    private final List<AuditEntry> audits = new ArrayList<>();

    InMemoryCrossAssociationStore(
            @Value("${guanxian.security.demo.association-id:00000000-0000-0000-0000-000000000106}") UUID associationId,
            @Value("${guanxian.security.demo.enterprise-id:00000000-0000-0000-0000-000000000201}") UUID enterpriseId) {
        enterpriseAssociations.put(enterpriseId, associationId);
    }

    @Override
    public synchronized List<CrossAssociationDtos.AccessRequestView> accessRequests() {
        return List.copyOf(accessRequests.values());
    }

    @Override
    public synchronized Optional<CrossAssociationDtos.AccessRequestView> accessRequest(UUID id) {
        return Optional.ofNullable(accessRequests.get(id));
    }

    @Override
    public synchronized CrossAssociationDtos.AccessRequestView insertAccessRequest(
            UUID source, UUID target, String reason, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        var value = new CrossAssociationDtos.AccessRequestView(
                id, source, target, reason, "PENDING", actor.subject(), null, null, now, null);
        accessRequests.put(id, value);
        return value;
    }

    @Override
    public synchronized CrossAssociationDtos.AccessRequestView reviewAccessRequest(
            UUID id, String status, String comment, ActorScope actor, Instant now) {
        var old = accessRequests.get(id);
        if (old == null || !"PENDING".equals(old.status())) {
            throw new ConflictException("access request is no longer pending");
        }
        var value = new CrossAssociationDtos.AccessRequestView(old.id(), old.applicantAssociationId(),
                old.targetAssociationId(), old.reason(), status, old.requestedBySubject(), actor.subject(),
                comment, old.requestedAt(), now);
        accessRequests.put(id, value);
        return value;
    }

    @Override
    public synchronized List<CrossAssociationDtos.RelationshipView> relationships() {
        return List.copyOf(relationships.values());
    }

    @Override
    public synchronized Optional<CrossAssociationDtos.RelationshipView> relationship(UUID source, UUID target) {
        return Optional.ofNullable(relationships.get(key(source, target)))
                .or(() -> Optional.ofNullable(relationships.get(key(target, source))));
    }

    @Override
    public synchronized CrossAssociationDtos.RelationshipView establishRelationship(
            UUID source, UUID target, boolean allowMemberData, Instant expiresAt, ActorScope actor, Instant now) {
        var old = relationship(source, target).orElse(null);
        if (old == null) {
            var created = new CrossAssociationDtos.RelationshipView(source, target, "ACTIVE", allowMemberData,
                    expiresAt, null, null, null, null, 0, now, now);
            relationships.put(key(source, target), created);
            return created;
        }
        var changed = new CrossAssociationDtos.RelationshipView(old.sourceAssociationId(), old.targetAssociationId(),
                "ACTIVE", allowMemberData, expiresAt, null, null, null, null,
                old.version() + 1, old.createdAt(), now);
        relationships.put(key(old.sourceAssociationId(), old.targetAssociationId()), changed);
        return changed;
    }

    @Override
    public synchronized CrossAssociationDtos.RelationshipView updateRelationship(
            UUID source, UUID target, long expectedVersion, String status, Instant expiresAt,
            Instant suspendedAt, Instant revokedAt, String reason, ActorScope actor, Instant now) {
        var old = relationship(source, target).orElseThrow(() -> new ConflictException("relationship no longer exists"));
        requireVersion(old.version(), expectedVersion);
        var changed = new CrossAssociationDtos.RelationshipView(old.sourceAssociationId(), old.targetAssociationId(),
                status, old.allowMemberData(), expiresAt, suspendedAt, revokedAt,
                "REVOKED".equals(status) ? actor.subject() : old.revokedBySubject(),
                "REVOKED".equals(status) ? reason : old.revokeReason(), old.version() + 1, old.createdAt(), now);
        relationships.put(key(old.sourceAssociationId(), old.targetAssociationId()), changed);
        return changed;
    }

    @Override
    public synchronized List<CrossAssociationDtos.SharePolicyView> sharePolicies() {
        return List.copyOf(policies.values());
    }

    @Override
    public synchronized Optional<CrossAssociationDtos.SharePolicyView> sharePolicy(UUID id) {
        return Optional.ofNullable(policies.get(id));
    }

    @Override
    public synchronized CrossAssociationDtos.SharePolicyView insertSharePolicy(
            UUID source, CrossAssociationDtos.SharePolicyUpsert request, ActorScope actor, Instant now) {
        boolean duplicate = policies.values().stream().anyMatch(item -> item.sourceAssociationId().equals(source)
                && item.targetAssociationId().equals(request.targetAssociationId())
                && item.resourceType().equals(request.resourceType()));
        if (duplicate) {
            throw new ConflictException("a share policy already exists for this resource type");
        }
        UUID id = UUID.randomUUID();
        var created = new CrossAssociationDtos.SharePolicyView(id, source, request.targetAssociationId(),
                request.resourceType(), request.visibleFields(), request.status(), request.validFrom(),
                request.expiresAt(), actor.subject(), 0, now, now);
        policies.put(id, created);
        return created;
    }

    @Override
    public synchronized CrossAssociationDtos.SharePolicyView updateSharePolicy(
            UUID id, long expectedVersion, CrossAssociationDtos.SharePolicyUpsert request,
            ActorScope actor, Instant now) {
        var old = policies.get(id);
        if (old == null) {
            throw new ConflictException("share policy no longer exists");
        }
        requireVersion(old.version(), expectedVersion);
        var changed = new CrossAssociationDtos.SharePolicyView(id, old.sourceAssociationId(),
                request.targetAssociationId(), request.resourceType(), request.visibleFields(), request.status(),
                request.validFrom(), request.expiresAt(), old.createdBySubject(), old.version() + 1,
                old.createdAt(), now);
        policies.put(id, changed);
        return changed;
    }

    @Override
    public synchronized List<CrossAssociationDtos.ConsentView> consents() {
        return List.copyOf(consents.values());
    }

    @Override
    public synchronized Optional<CrossAssociationDtos.ConsentView> consent(UUID id) {
        return Optional.ofNullable(consents.get(id));
    }

    @Override
    public synchronized CrossAssociationDtos.ConsentView insertConsent(
            UUID enterpriseId, CrossAssociationDtos.ConsentCreate request, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        var created = new CrossAssociationDtos.ConsentView(id, enterpriseId, request.targetAssociationId(),
                request.resourceType().trim().toUpperCase(), request.resourceId(), "ACTIVE", actor.subject(),
                request.expiresAt(), null, now);
        consents.put(id, created);
        return created;
    }

    @Override
    public synchronized CrossAssociationDtos.ConsentView revokeConsent(UUID id, ActorScope actor, Instant now) {
        var old = consents.get(id);
        if (old == null || !"ACTIVE".equals(old.status())) {
            throw new ConflictException("share consent is no longer active");
        }
        var revoked = new CrossAssociationDtos.ConsentView(old.id(), old.enterpriseId(), old.targetAssociationId(),
                old.resourceType(), old.resourceId(), "REVOKED", old.grantedBySubject(), old.expiresAt(), now,
                old.createdAt());
        consents.put(id, revoked);
        return revoked;
    }

    @Override
    public synchronized List<CrossAssociationDtos.RecommendationView> recommendations() {
        return List.copyOf(recommendations.values());
    }

    @Override
    public synchronized Optional<CrossAssociationDtos.RecommendationView> recommendation(UUID id) {
        return Optional.ofNullable(recommendations.get(id));
    }

    @Override
    public synchronized CrossAssociationDtos.RecommendationView insertRecommendation(
            UUID source, CrossAssociationDtos.RecommendationCreate request, ActorScope actor, Instant now) {
        UUID id = UUID.randomUUID();
        var created = new CrossAssociationDtos.RecommendationView(id, source, request.targetAssociationId(),
                request.demandId(), request.matchId(), "PENDING_REVIEW", request.summary().trim(), actor.subject(),
                null, null, now, null, 0);
        recommendations.put(id, created);
        return created;
    }

    @Override
    public synchronized CrossAssociationDtos.RecommendationView reviewRecommendation(
            UUID id, long expectedVersion, String status, String comment, ActorScope actor, Instant now) {
        var old = recommendations.get(id);
        if (old == null || !"PENDING_REVIEW".equals(old.status())) {
            throw new ConflictException("recommendation is no longer pending review");
        }
        requireVersion(old.version(), expectedVersion);
        var reviewed = new CrossAssociationDtos.RecommendationView(old.id(), old.sourceAssociationId(),
                old.targetAssociationId(), old.demandId(), old.matchId(), status, old.summary(),
                old.createdBySubject(), actor.subject(), comment, old.createdAt(), now, old.version() + 1);
        recommendations.put(id, reviewed);
        return reviewed;
    }

    @Override
    public synchronized Optional<UUID> enterpriseAssociation(UUID enterpriseId) {
        return Optional.ofNullable(enterpriseAssociations.get(enterpriseId));
    }

    @Override
    public boolean associationExists(UUID associationId) {
        return associationId != null;
    }

    @Override
    public synchronized void audit(ActorScope actor, UUID associationId, UUID enterpriseId,
                                   String action, String resourceType, Object resourceId, Object details) {
        audits.add(new AuditEntry(actor.subject(), associationId, enterpriseId, action, resourceType,
                String.valueOf(resourceId), Instant.now()));
    }

    synchronized List<AuditEntry> auditEntries() {
        return List.copyOf(audits);
    }

    synchronized void bindEnterprise(UUID enterpriseId, UUID associationId) {
        enterpriseAssociations.put(enterpriseId, associationId);
    }

    private static String key(UUID source, UUID target) {
        return source + ":" + target;
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new PreconditionFailedException("resource version does not match If-Match");
        }
    }

    record AuditEntry(String actorSubject, UUID associationId, UUID enterpriseId, String action,
                      String resourceType, String resourceId, Instant occurredAt) {
    }
}
