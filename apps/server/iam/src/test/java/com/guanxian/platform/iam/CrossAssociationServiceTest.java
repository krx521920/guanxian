package com.guanxian.platform.iam;

import com.guanxian.platform.shared.error.ConflictException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossAssociationServiceTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000107");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID OTHER_PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000302");
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
        var reviewed = service.reviewAccessRequest(created.id(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, "approved", Instant.now().plusSeconds(3600), true),
                reviewer(TARGET));

        assertEquals("APPROVED", reviewed.status());
        assertTrue(service.relationships(reviewer(SOURCE)).getFirst().allowMemberData());
        assertEquals(List.of("ASSOCIATION_ACCESS_REQUEST_CREATE", "ASSOCIATION_ACCESS_REQUEST_APPROVED",
                        "ASSOCIATION_RELATIONSHIP_ESTABLISH"),
                store.auditEntries().stream().map(InMemoryCrossAssociationStore.AuditEntry::action).toList());
    }

    @Test
    void accessReviewIsTargetScoped() {
        var created = service.createAccessRequest(
                new CrossAssociationDtos.AccessRequestCreate(null, TARGET, null), reviewer(SOURCE));
        assertThrows(ForbiddenException.class, () -> service.reviewAccessRequest(created.id(),
                new CrossAssociationDtos.AccessRequestReview(
                        CrossAssociationDtos.AccessDecision.REJECT, null, null, null), reviewer(SOURCE)));
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
        assertThrows(ConflictException.class, () -> isolatedService.changeRelationship(SOURCE, TARGET, expired.version(),
                new CrossAssociationDtos.RelationshipChange(
                        CrossAssociationDtos.RelationshipAction.ACTIVATE, Instant.now().plusSeconds(3600), null),
                reviewer(SOURCE)));
    }

    @Test
    void consentRequiresSupportedResourceOwnedByBoundEnterprise() {
        establishRelationship();
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
    void managementReadsRequireAssociationStaffAndEnterpriseReadsAreOwned() {
        establishRelationship();
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
        service.reviewAccessRequest(request.id(), new CrossAssociationDtos.AccessRequestReview(
                CrossAssociationDtos.AccessDecision.APPROVE, null, null, true), reviewer(TARGET));
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
}
