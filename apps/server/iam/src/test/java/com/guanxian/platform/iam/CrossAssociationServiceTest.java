package com.guanxian.platform.iam;

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
    private InMemoryCrossAssociationStore store;
    private CrossAssociationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryCrossAssociationStore(SOURCE, ENTERPRISE);
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
    void enterpriseConsentIsLimitedToBoundEnterprise() {
        establishRelationship();
        assertThrows(ForbiddenException.class, () -> service.grantConsent(
                new CrossAssociationDtos.ConsentCreate(UUID.randomUUID(), TARGET, "PRODUCT", null, null),
                enterpriseAdmin()));

        var consent = service.grantConsent(new CrossAssociationDtos.ConsentCreate(
                null, TARGET, "PRODUCT", UUID.randomUUID(), Instant.now().plusSeconds(3600)), enterpriseAdmin());
        assertEquals(ENTERPRISE, consent.enterpriseId());
    }

    @Test
    void recommendationReviewIsTargetScopedAndVersioned() {
        establishRelationship();
        var created = service.createRecommendation(new CrossAssociationDtos.RecommendationCreate(
                null, TARGET, UUID.randomUUID(), null, "need supplier"), enterpriseAdmin());
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

    private static ActorScope enterpriseAdmin() {
        return new ActorScope(UUID.randomUUID(), "enterprise-admin", "enterprise-admin", SOURCE,
                ENTERPRISE, Set.of("ENTERPRISE_ADMIN"), Set.of(TARGET));
    }
}
