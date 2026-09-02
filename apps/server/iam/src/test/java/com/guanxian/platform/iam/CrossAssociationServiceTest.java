package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossAssociationServiceTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000107");
    private static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-000000000108");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OTHER_PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000311");
    private static final UUID OTHER_SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000312");
    private static final UUID DEMAND = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OTHER_DEMAND = UUID.fromString("00000000-0000-0000-0000-000000000402");
    private static final UUID MATCH = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID OTHER_MATCH = UUID.fromString("00000000-0000-0000-0000-000000000502");

    private InMemoryCrossAssociationStore store;
    private CrossAssociationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryCrossAssociationStore(SOURCE, ENTERPRISE);
        store.bindEnterprise(OTHER_ENTERPRISE, SOURCE);
        store.bindResource("PRODUCT", PRODUCT, ENTERPRISE);
        store.bindResource("PRODUCT", OTHER_PRODUCT, OTHER_ENTERPRISE);
        store.bindResource("SERVICE", SERVICE, ENTERPRISE);
        store.bindResource("SERVICE", OTHER_SERVICE, OTHER_ENTERPRISE);
        store.bindDemand(DEMAND, ENTERPRISE, SOURCE);
        store.bindDemand(OTHER_DEMAND, OTHER_ENTERPRISE, SOURCE);
        store.bindMatch(MATCH, DEMAND, ENTERPRISE, SOURCE, OTHER_ENTERPRISE, SOURCE);
        store.bindMatch(OTHER_MATCH, OTHER_DEMAND, OTHER_ENTERPRISE, SOURCE, OTHER_ENTERPRISE, SOURCE);
        service = new CrossAssociationService(store);
    }

    @Test
    void approvalCreatesActiveRelationshipAndAuditsEveryWrite() {
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, "cooperation"), reviewer(SOURCE));
        var reviewed = service.reviewAccessRequest(created.id(), created.version(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, "approved", Instant.now().plusSeconds(3600), true),
                reviewer(TARGET));

        assertEquals("APPROVED", reviewed.status());
        assertTrue(service.relationships(reviewer(SOURCE)).getFirst().allowMemberData());
        assertEquals(List.of("ASSOCIATION_ACCESS_REQUEST_CREATE", "ASSOCIATION_ACCESS_REQUEST_APPROVED",
                        "ASSOCIATION_RELATIONSHIP_ESTABLISH", "ASSOCIATION_RELATIONSHIP_ESTABLISH"),
                store.auditEntries().stream().map(InMemoryCrossAssociationStore.AuditEntry::action).toList());
        var relationshipAudits = store.auditEntries().stream()
                .filter(entry -> entry.action().equals("ASSOCIATION_RELATIONSHIP_ESTABLISH")).toList();
        assertEquals(Set.of(SOURCE, TARGET), relationshipAudits.stream()
                .map(InMemoryCrossAssociationStore.AuditEntry::associationId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(relationshipAudits.stream().allMatch(entry -> Long.valueOf(0).equals(entry.resourceVersion())));
    }

    @Test
    void reversePendingAccessRequestIsRejected() {
        service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, "first"), reviewer(SOURCE));

        assertThrows(ConflictException.class, () -> service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, SOURCE, "reverse"), reviewer(TARGET)));
    }

    @Test
    void accessReviewIsTargetScoped() {
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));
        assertThrows(ForbiddenException.class, () -> service.reviewAccessRequest(created.id(), created.version(),
                new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.REJECT, null, null, null), reviewer(SOURCE)));
    }

    @Test
    void pendingAccessRequestCanOnlyBeCancelledByApplicant() {
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, "temporary"), reviewer(SOURCE));

        assertThrows(ForbiddenException.class, () -> service.cancelAccessRequest(created.id(), created.version(),
                new CrossAssociationDtos.AccessRequestCancel("wrong side"), reviewer(TARGET)));
        var cancelled = service.cancelAccessRequest(created.id(), created.version(),
                new CrossAssociationDtos.AccessRequestCancel("no longer needed"), reviewer(SOURCE));

        assertEquals("CANCELLED", cancelled.status());
        assertEquals("no longer needed", cancelled.reviewComment());
        assertEquals("ASSOCIATION_ACCESS_REQUEST_CANCEL", store.auditEntries().getLast().action());
        assertThrows(ConflictException.class, () -> service.reviewAccessRequest(created.id(), cancelled.version(),
                new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.APPROVE, null, null, true), reviewer(TARGET)));
    }

    @Test
    void accessRequestRejectsAStaleVersionBeforeWriting() {
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));

        assertThrows(PreconditionFailedException.class, () -> service.reviewAccessRequest(
                created.id(), created.version() + 1,
                new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.REJECT, null, null, null), reviewer(TARGET)));
        assertEquals("PENDING", store.accessRequest(created.id()).orElseThrow().status());
    }

    @Test
    void omittedMemberDataApprovalDefaultsToFailClosed() {
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));
        service.reviewAccessRequest(created.id(), created.version(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, null, null, null), reviewer(TARGET));

        assertEquals(false, service.relationships(reviewer(SOURCE)).getFirst().allowMemberData());
    }

    @Test
    void relationshipMutationRequiresCurrentVersion() {
        establishRelationship();
        assertThrows(PreconditionFailedException.class, () -> service.changeRelationship(SOURCE, TARGET, 99,
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.SUSPEND, null, null), reviewer(SOURCE)));
    }

    @Test
    void onlySuspendingAssociationCanReactivateAndExpiredRelationshipCannotReactivate() {
        establishRelationship();
        var suspended = service.changeRelationship(SOURCE, TARGET, 0,
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.SUSPEND, null, "maintenance"), reviewer(SOURCE));
        assertThrows(ForbiddenException.class, () -> service.changeRelationship(SOURCE, TARGET, suspended.version(),
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.ACTIVATE, null, null), reviewer(TARGET)));
        var active = service.changeRelationship(SOURCE, TARGET, suspended.version(),
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.ACTIVATE, null, null), reviewer(SOURCE));
        assertEquals("ACTIVE", active.status());

        var isolatedStore = new InMemoryCrossAssociationStore(SOURCE, ENTERPRISE);
        var isolatedService = new CrossAssociationService(isolatedStore);
        isolatedStore.establishRelationship(SOURCE, TARGET, true, Instant.now().minusSeconds(1),
                reviewer(SOURCE), Instant.now().minusSeconds(2));
        var expired = isolatedService.changeRelationship(SOURCE, TARGET, 0,
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.EXPIRE, null, null), reviewer(SOURCE));
        assertThrows(ApiException.class, () -> isolatedService.changeRelationship(SOURCE, TARGET, expired.version(),
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.ACTIVATE, Instant.now().plusSeconds(3600), null),
                reviewer(SOURCE)));
    }

    @Test
    void relationshipActivationCannotUnilaterallyExtendExpiry() {
        Instant expiry = Instant.now().plusSeconds(1800);
        var request = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));
        service.reviewAccessRequest(request.id(), request.version(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, null, expiry, true), reviewer(TARGET));
        var suspended = service.changeRelationship(SOURCE, TARGET, 0,
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.SUSPEND, null, null), reviewer(SOURCE));

        assertThrows(ApiException.class, () -> service.changeRelationship(SOURCE, TARGET, suspended.version(),
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.ACTIVATE, expiry.plusSeconds(3600), null),
                reviewer(SOURCE)));
        assertEquals(expiry, store.relationship(SOURCE, TARGET).orElseThrow().expiresAt());
    }

    @Test
    void terminalRelationshipTransitionsAreOneWayAndClearTransientState() {
        establishRelationship();
        var suspended = service.changeRelationship(SOURCE, TARGET, 0,
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.SUSPEND, null, "maintenance"), reviewer(SOURCE));
        var revoked = service.changeRelationship(SOURCE, TARGET, suspended.version(),
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.REVOKE, null, "partnership ended"), reviewer(SOURCE));

        assertEquals("REVOKED", revoked.status());
        assertNull(revoked.suspendedAt());
        assertNull(revoked.suspendedByAssociationId());
        assertNull(revoked.suspendedBySubject());
        assertTrue(revoked.revokedAt() != null);
        assertEquals("partnership ended", revoked.revokeReason());
        assertThrows(ConflictException.class, () -> service.changeRelationship(
                SOURCE, TARGET, revoked.version(), new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.EXPIRE, null, null), reviewer(SOURCE)));

        var isolatedStore = new InMemoryCrossAssociationStore(SOURCE, ENTERPRISE);
        var isolatedService = new CrossAssociationService(isolatedStore);
        Instant expiry = Instant.now().minusSeconds(1);
        isolatedStore.establishRelationship(SOURCE, TARGET, true, expiry,
                reviewer(SOURCE), expiry.minusSeconds(1));
        var expired = isolatedService.changeRelationship(SOURCE, TARGET, 0,
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.EXPIRE, null, null), reviewer(SOURCE));

        assertEquals("EXPIRED", expired.status());
        assertNull(expired.suspendedAt());
        assertNull(expired.revokedAt());
        assertThrows(ConflictException.class, () -> isolatedService.changeRelationship(
                SOURCE, TARGET, expired.version(), new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.REVOKE, null, "too late"), reviewer(SOURCE)));
    }

    @Test
    void consentRequiresSupportedResourceOwnedByBoundEnterprise() {
        establishRelationship();
        createProductPolicy();
        assertThrows(ForbiddenException.class, () -> service.grantConsent(
                new CrossAssociationDtos.ConsentCreate(null, TARGET, "PRODUCT", OTHER_PRODUCT, null),
                enterpriseAdmin(ENTERPRISE)));
        assertThrows(RuntimeException.class, () -> service.grantConsent(
                new CrossAssociationDtos.ConsentCreate(null, TARGET, "SECRET", PRODUCT, null),
                enterpriseAdmin(ENTERPRISE)));

        var consent = service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "product", PRODUCT, Instant.now().plusSeconds(3600)),
                enterpriseAdmin(ENTERPRISE));
        assertEquals(ENTERPRISE, consent.enterpriseId());
        assertEquals("PRODUCT", consent.resourceType());
    }

    @Test
    void sharePolicyIsWhitelistedVersionedAndControlsConsentTargets() {
        establishRelationship();
        assertThrows(ApiException.class, () -> service.createSharePolicy(
                new CrossAssociationDtos.SharePolicyUpsert(
                        null, TARGET, "PRODUCT", List.of("name", "secretField"), null, null, null),
                reviewer(SOURCE)));
        assertThrows(ApiException.class, () -> service.createSharePolicy(
                new CrossAssociationDtos.SharePolicyUpsert(
                        null, TARGET, "DEMAND", List.of("description"), null, null, null), reviewer(SOURCE)));

        Instant now = Instant.now();
        var policy = service.createSharePolicy(new CrossAssociationDtos.SharePolicyUpsert(
                null, TARGET, "PRODUCT", List.of("name", "description"), null,
                now.plusSeconds(3600), null), reviewer(SOURCE));
        Instant restrictiveExpiry = now.plusSeconds(1800);
        var merged = CrossAssociationService.mostRestrictiveTarget(
                new CrossAssociationDtos.ConsentTargetView(TARGET, "PRODUCT", null),
                new CrossAssociationDtos.ConsentTargetView(TARGET, "PRODUCT", restrictiveExpiry));
        assertEquals(restrictiveExpiry, merged.policyExpiresAt());
        var targets = service.consentTargets(enterpriseAdmin(ENTERPRISE));
        assertEquals(1, targets.size());
        assertEquals(policy.expiresAt(), targets.getFirst().policyExpiresAt());
        assertThrows(ApiException.class, () -> service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, null), enterpriseAdmin(ENTERPRISE)));
        assertEquals(PRODUCT, service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, now.plusSeconds(1200)),
                enterpriseAdmin(ENTERPRISE)).resourceId());
        assertThrows(ConflictException.class, () -> service.createSharePolicy(
                new CrossAssociationDtos.SharePolicyUpsert(
                        null, TARGET, "PRODUCT", List.of("name"), null, null, null), reviewer(SOURCE)));

        var suspended = service.changeSharePolicyStatus(policy.id(), policy.version(),
                new CrossAssociationDtos.SharePolicyStatusChange(
                        CrossAssociationDtos.SharePolicyStatus.SUSPENDED), reviewer(SOURCE));
        assertTrue(service.consentTargets(enterpriseAdmin(ENTERPRISE)).isEmpty());
        var active = service.changeSharePolicyStatus(suspended.id(), suspended.version(),
                new CrossAssociationDtos.SharePolicyStatusChange(
                        CrossAssociationDtos.SharePolicyStatus.ACTIVE), reviewer(SOURCE));
        assertEquals("ACTIVE", active.status());
        assertThrows(PreconditionFailedException.class, () -> service.changeSharePolicyStatus(
                active.id(), suspended.version(), new CrossAssociationDtos.SharePolicyStatusChange(
                        CrossAssociationDtos.SharePolicyStatus.SUSPENDED), reviewer(SOURCE)));
    }

    @Test
    void activeConsentIsUniqueAndFieldsRequirePolicyAndConsent() {
        establishRelationship();
        var policy = service.createSharePolicy(new CrossAssociationDtos.SharePolicyUpsert(
                null, TARGET, "PRODUCT", List.of("name", "description"), null, null, null), reviewer(SOURCE));
        service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, null), enterpriseAdmin(ENTERPRISE));
        assertThrows(ConflictException.class, () -> service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, null), enterpriseAdmin(ENTERPRISE)));

        var authorization = new CrossAssociationFieldAuthorizationService(store);
        assertEquals(Set.of("name", "description"), authorization.authorizedFields(
                partnerReader(TARGET), ENTERPRISE, "PRODUCT", PRODUCT).orElseThrow());
        service.changeSharePolicyStatus(policy.id(), policy.version(),
                new CrossAssociationDtos.SharePolicyStatusChange(
                        CrossAssociationDtos.SharePolicyStatus.SUSPENDED), reviewer(SOURCE));
        assertTrue(authorization.authorizedFields(
                partnerReader(TARGET), ENTERPRISE, "PRODUCT", PRODUCT).isEmpty());
    }

    @Test
    void invalidStoredPolicyFailsClosedInsteadOfAuthorizingItsAllowedSubset() {
        establishRelationship();
        Instant now = Instant.now();
        store.insertSharePolicy(SOURCE, new CrossAssociationDtos.SharePolicyUpsert(
                SOURCE, TARGET, "PRODUCT", List.of("name", "unsupportedField"),
                now, null, "ACTIVE"), reviewer(SOURCE), now);
        store.insertConsent(ENTERPRISE, new CrossAssociationDtos.ConsentCreate(
                ENTERPRISE, TARGET, "PRODUCT", PRODUCT, null), enterpriseAdmin(ENTERPRISE), now);

        assertTrue(new CrossAssociationFieldAuthorizationService(store).authorizedFields(
                partnerReader(TARGET), ENTERPRISE, "PRODUCT", PRODUCT).isEmpty());
    }

    @Test
    void expiredActiveConsentIsMaterializedAndAuditedBeforeRegrant() {
        establishRelationship();
        createProductPolicy();
        Instant now = Instant.now();
        var expired = store.insertConsent(ENTERPRISE, new CrossAssociationDtos.ConsentCreate(
                ENTERPRISE, TARGET, "PRODUCT", PRODUCT, now.minusSeconds(60)),
                enterpriseAdmin(ENTERPRISE), now.minusSeconds(120));

        var replacement = service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, null), enterpriseAdmin(ENTERPRISE));

        assertEquals("EXPIRED", store.consent(expired.id()).orElseThrow().status());
        assertEquals("ACTIVE", replacement.status());
        assertTrue(store.auditEntries().stream().anyMatch(entry ->
                entry.action().equals("ENTERPRISE_SHARE_CONSENT_EXPIRE")
                        && entry.resourceId().equals(expired.id().toString())));
    }

    @Test
    void relationshipExpiryBoundsConsentAndRevocationPreventsConsentRevival() {
        Instant now = Instant.now();
        Instant relationshipExpiry = now.plusSeconds(1800);
        var request = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));
        service.reviewAccessRequest(request.id(), request.version(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, null, relationshipExpiry, true), reviewer(TARGET));
        service.createSharePolicy(new CrossAssociationDtos.SharePolicyUpsert(
                null, TARGET, "PRODUCT", List.of("name"), null, now.plusSeconds(3600), null),
                reviewer(SOURCE));

        assertEquals(relationshipExpiry,
                service.consentTargets(enterpriseAdmin(ENTERPRISE)).getFirst().policyExpiresAt());
        assertThrows(ApiException.class, () -> service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, now.plusSeconds(2400)), enterpriseAdmin(ENTERPRISE)));
        var consent = service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, now.plusSeconds(1200)), enterpriseAdmin(ENTERPRISE));

        service.changeRelationship(SOURCE, TARGET, 0, new CrossAssociationDtos.RelationshipChange(
                CrossAssociationDtos.RelationshipAction.REVOKE, null, "partnership ended"), reviewer(SOURCE));
        assertEquals("REVOKED", store.consent(consent.id()).orElseThrow().status());
        var fresh = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, "reconnect"), reviewer(SOURCE));
        service.reviewAccessRequest(fresh.id(), fresh.version(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, null, null, true), reviewer(TARGET));

        assertTrue(new CrossAssociationFieldAuthorizationService(store).authorizedFields(
                partnerReader(TARGET), ENTERPRISE, "PRODUCT", PRODUCT).isEmpty());
        assertTrue(store.auditEntries().stream().anyMatch(entry ->
                entry.action().equals("ENTERPRISE_SHARE_CONSENT_INVALIDATE_RELATIONSHIP_REVOKE")));
    }

    @Test
    void forgedConsentNeverAuthorizesResourceOwnedByAnotherEnterpriseForAnyType() {
        establishRelationship();
        Map<String, UUID> foreignResources = Map.of(
                "MEMBER", OTHER_ENTERPRISE,
                "PRODUCT", OTHER_PRODUCT,
                "SERVICE", OTHER_SERVICE,
                "DEMAND", OTHER_DEMAND,
                "MATCH", OTHER_MATCH);
        Map<String, List<String>> policyFields = Map.of(
                "MEMBER", List.of("name"),
                "PRODUCT", List.of("name"),
                "SERVICE", List.of("name"),
                "DEMAND", List.of("title"),
                "MATCH", List.of("state"));
        Instant now = Instant.now();
        var authorization = new CrossAssociationFieldAuthorizationService(store);

        foreignResources.forEach((resourceType, resourceId) -> {
            service.createSharePolicy(new CrossAssociationDtos.SharePolicyUpsert(
                    null, TARGET, resourceType, policyFields.get(resourceType), null, null, null),
                    reviewer(SOURCE));
            store.insertConsent(ENTERPRISE, new CrossAssociationDtos.ConsentCreate(
                    ENTERPRISE, TARGET, resourceType, resourceId, null), enterpriseAdmin(ENTERPRISE), now);
            assertTrue(authorization.authorizedFields(
                    partnerReader(TARGET), ENTERPRISE, resourceType, resourceId).isEmpty(), resourceType);
        });
    }

    @Test
    void recommendationRequiresOwnedConsistentResources() {
        establishRelationship();
        assertThrows(ForbiddenException.class, () -> service.createRecommendation(
                new CrossAssociationDtos.RecommendationCreate(
                        null, TARGET, OTHER_DEMAND, null, "foreign demand"),
                enterpriseAdmin(ENTERPRISE)));
        assertThrows(RuntimeException.class, () -> service.createRecommendation(
                new CrossAssociationDtos.RecommendationCreate(
                        null, TARGET, DEMAND, OTHER_MATCH, "mismatched"), reviewer(SOURCE)));

        var created = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, DEMAND, MATCH, "need supplier"), enterpriseAdmin(ENTERPRISE));
        assertEquals(DEMAND, created.demandId());
        assertEquals(MATCH, created.matchId());
    }

    @Test
    void matchOnlyRecommendationAllowsSourceAssociationCandidateParticipant() {
        establishRelationship();
        store.bindEnterprise(OTHER_ENTERPRISE, TARGET);
        store.bindDemand(OTHER_DEMAND, OTHER_ENTERPRISE, TARGET);
        store.bindMatch(OTHER_MATCH, OTHER_DEMAND, OTHER_ENTERPRISE, TARGET, ENTERPRISE, SOURCE);

        var created = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, null, OTHER_MATCH, "candidate-side recommendation"),
                enterpriseAdmin(ENTERPRISE));

        assertNull(created.demandId());
        assertEquals(OTHER_MATCH, created.matchId());
    }

    @Test
    void recommendationReviewRequiresStillActiveRelationship() {
        establishRelationship();
        var created = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, DEMAND, null, "need supplier"), enterpriseAdmin(ENTERPRISE));
        service.changeRelationship(SOURCE, TARGET, 0, new CrossAssociationDtos.RelationshipChange(
                CrossAssociationDtos.RelationshipAction.SUSPEND, null, "pause"), reviewer(SOURCE));

        assertThrows(ForbiddenException.class, () -> service.reviewRecommendation(created.id(), 0,
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.APPROVE, null), reviewer(TARGET)));
    }

    @Test
    void recommendationApprovalRevalidatesPersistedResourceOwnershipAndConsistency() {
        establishRelationship();
        Instant now = Instant.now();
        var ownershipDrift = store.insertRecommendation(SOURCE,
                new CrossAssociationDtos.RecommendationCreate(
                        SOURCE, TARGET, DEMAND, null, "legacy demand"), reviewer(SOURCE), now);
        store.bindDemand(DEMAND, ENTERPRISE, OUTSIDER);

        assertThrows(ForbiddenException.class, () -> service.reviewRecommendation(ownershipDrift.id(), 0,
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.APPROVE, null), reviewer(TARGET)));
        assertEquals("REJECTED", service.reviewRecommendation(ownershipDrift.id(), 0,
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.REJECT, "invalid legacy resource"),
                reviewer(TARGET)).status());

        store.bindDemand(DEMAND, ENTERPRISE, SOURCE);
        var mismatched = store.insertRecommendation(SOURCE,
                new CrossAssociationDtos.RecommendationCreate(
                        SOURCE, TARGET, DEMAND, OTHER_MATCH, "legacy mismatch"), reviewer(SOURCE), now);
        assertThrows(ApiException.class, () -> service.reviewRecommendation(mismatched.id(), 0,
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.APPROVE, null), reviewer(TARGET)));
    }

    @Test
    void recommendationWritesEveryVersionToBothAssociationAuditDomains() {
        establishRelationship();
        var created = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, DEMAND, null, "bilateral audit"), reviewer(SOURCE));

        var createAudits = store.auditEntries().stream()
                .filter(entry -> entry.action().equals("CROSS_ASSOCIATION_RECOMMENDATION_CREATE"))
                .toList();
        assertEquals(2, createAudits.size());
        assertEquals(Set.of(SOURCE, TARGET), createAudits.stream()
                .map(InMemoryCrossAssociationStore.AuditEntry::associationId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(createAudits.stream().allMatch(entry -> Long.valueOf(0).equals(entry.resourceVersion())));

        var approved = service.reviewRecommendation(created.id(), created.version(),
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.APPROVE, "accepted"), reviewer(TARGET));
        var approvalAudits = store.auditEntries().stream()
                .filter(entry -> entry.action().equals("CROSS_ASSOCIATION_RECOMMENDATION_APPROVED"))
                .toList();
        assertEquals(2, approvalAudits.size());
        assertEquals(Set.of(SOURCE, TARGET), approvalAudits.stream()
                .map(InMemoryCrossAssociationStore.AuditEntry::associationId)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(approvalAudits.stream().allMatch(entry ->
                Long.valueOf(approved.version()).equals(entry.resourceVersion())));
    }

    @Test
    void managementReadsRequireAssociationStaffAndEnterpriseReadsAreOwned() {
        establishRelationship();
        createProductPolicy();
        service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, null), enterpriseAdmin(ENTERPRISE));
        service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", OTHER_PRODUCT, null), enterpriseAdmin(OTHER_ENTERPRISE));
        service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, DEMAND, null, "own"), enterpriseAdmin(ENTERPRISE));
        service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, OTHER_DEMAND, null, "other"), reviewer(SOURCE));

        assertThrows(ForbiddenException.class, () -> service.relationships(enterpriseAdmin(ENTERPRISE)));
        assertEquals(1, service.consents(enterpriseAdmin(ENTERPRISE)).size());
        assertEquals(1, service.recommendations(enterpriseAdmin(ENTERPRISE)).size());
        assertThrows(ForbiddenException.class, () -> service.consents(observer()));
    }

    @Test
    void systemAdministratorGlobalReadsAreAllowedButEveryWriteRequiresSelectedAssociation() {
        ActorScope global = systemAdministrator(null, null);
        assertTrue(service.accessRequests(global).isEmpty());
        assertThrows(ForbiddenException.class, () -> service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(SOURCE, TARGET, "global write"), global));

        ActorScope sourceContext = systemAdministrator(SOURCE, null);
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, "selected source"), sourceContext);

        assertEquals(1, service.accessRequests(global).size());
        assertEquals(1, service.accessRequests(sourceContext).size());
        assertEquals(1, service.accessRequests(systemAdministrator(TARGET, null)).size());
        assertTrue(service.accessRequests(systemAdministrator(OUTSIDER, null)).isEmpty());
        assertThrows(ForbiddenException.class, () -> service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(OUTSIDER, TARGET, "body override"), sourceContext));
        assertThrows(ForbiddenException.class, () -> service.reviewAccessRequest(
                created.id(), created.version(), new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.REJECT, null, null, null), global));
        assertThrows(ForbiddenException.class, () -> service.reviewAccessRequest(
                created.id(), created.version(), new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.REJECT, null, null, null), sourceContext));

        var reviewed = service.reviewAccessRequest(
                created.id(), created.version(), new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.REJECT, null, null, null),
                systemAdministrator(TARGET, null));
        assertEquals("REJECTED", reviewed.status());
    }

    @Test
    void selectedSystemEnterpriseBoundsConsentAndRecommendationData() {
        establishRelationship();
        createProductPolicy();
        ActorScope selectedEnterprise = systemAdministrator(SOURCE, ENTERPRISE);
        ActorScope selectedOtherEnterprise = systemAdministrator(SOURCE, OTHER_ENTERPRISE);
        ActorScope global = systemAdministrator(null, null);

        var consent = service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", PRODUCT, null), selectedEnterprise);
        assertEquals(ENTERPRISE, consent.enterpriseId());
        assertEquals(1, service.consents(selectedEnterprise).size());
        assertTrue(service.consents(selectedOtherEnterprise).isEmpty());
        assertEquals(1, service.consents(global).size());
        assertThrows(ForbiddenException.class, () -> service.grantConsent(
                new CrossAssociationDtos.ConsentCreate(
                        OTHER_ENTERPRISE, TARGET, "PRODUCT", OTHER_PRODUCT, null), selectedEnterprise));

        var recommendation = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, DEMAND, null, "selected enterprise demand"), selectedEnterprise);
        assertEquals(DEMAND, recommendation.demandId());
        assertEquals(1, service.recommendations(selectedEnterprise).size());
        assertTrue(service.recommendations(selectedOtherEnterprise).isEmpty());
        assertThrows(ForbiddenException.class, () -> service.createRecommendation(
                new CrossAssociationDtos.RecommendationCreate(
                        null, TARGET, OTHER_DEMAND, null, "foreign enterprise demand"), selectedEnterprise));
    }

    @Test
    void recommendationReviewIsTargetScopedAndVersioned() {
        establishRelationship();
        var created = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, DEMAND, null, "need supplier"), enterpriseAdmin(ENTERPRISE));
        assertThrows(ForbiddenException.class, () -> service.reviewRecommendation(created.id(), 0,
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.APPROVE, null), reviewer(SOURCE)));
        var reviewed = service.reviewRecommendation(created.id(), 0,
                new CrossAssociationDtos.RecommendationReview(
                        CrossAssociationDtos.RecommendationDecision.APPROVE, "recommended"), reviewer(TARGET));
        assertEquals(1, reviewed.version());
    }

    @Test
    void etagMustBeOneStrongVersion() {
        assertEquals(7, VersionEtags.requiredVersion(List.of("\"7\"")));
        assertThrows(RuntimeException.class, () -> VersionEtags.requiredVersion(List.of("W/\"7\"")));
        assertThrows(RuntimeException.class, () -> VersionEtags.requiredVersion(List.of("\"1\"", "\"2\"")));
    }

    private void establishRelationship() {
        var request = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));
        service.reviewAccessRequest(request.id(), request.version(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, null, null, true), reviewer(TARGET));
    }

    private CrossAssociationDtos.SharePolicyView createProductPolicy() {
        return service.createSharePolicy(new CrossAssociationDtos.SharePolicyUpsert(
                null, TARGET, "PRODUCT", List.of("name"), null, null, null), reviewer(SOURCE));
    }

    private static ActorScope reviewer(UUID associationId) {
        return new ActorScope(UUID.randomUUID(), "reviewer", "reviewer", associationId,
                null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }

    private static ActorScope enterpriseAdmin(UUID enterpriseId) {
        return new ActorScope(UUID.randomUUID(), "enterprise-" + enterpriseId, "enterprise", SOURCE,
                enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of(TARGET));
    }

    private static ActorScope observer() {
        return new ActorScope(UUID.randomUUID(), "observer", "observer", SOURCE,
                null, Set.of("OBSERVER"), Set.of());
    }

    private static ActorScope partnerReader(UUID associationId) {
        return new ActorScope(UUID.randomUUID(), "partner-reader", "partner-reader", associationId,
                null, Set.of("ASSOCIATION_OPERATOR"), Set.of(SOURCE));
    }

    private static ActorScope systemAdministrator(UUID associationId, UUID enterpriseId) {
        return new ActorScope(UUID.randomUUID(), "system", "system", associationId,
                enterpriseId, Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
