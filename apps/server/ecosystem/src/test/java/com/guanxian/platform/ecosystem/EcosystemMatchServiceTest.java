package com.guanxian.platform.ecosystem;

import com.guanxian.platform.ai.AiTextService;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcosystemMatchServiceTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID DEMAND_ENTERPRISE = UUID.randomUUID();
    private static final UUID SUPPLIER_ENTERPRISE = UUID.randomUUID();
    private static final UUID PROFILE_ONLY_ENTERPRISE = UUID.randomUUID();
    private static final UUID ASSOCIATION_B =
            UUID.fromString("74000000-0000-0000-0000-000000000002");
    private static final UUID ASSOCIATION_C =
            UUID.fromString("74000000-0000-0000-0000-000000000003");
    private static final UUID CROSS_DEMAND_ENTERPRISE =
            UUID.fromString("74000000-0000-0000-0000-000000000101");
    private static final UUID CROSS_SUPPLIER_ENTERPRISE =
            UUID.fromString("74000000-0000-0000-0000-000000000102");
    private static final UUID CROSS_MATCH_ID =
            UUID.fromString("74000000-0000-0000-0000-000000000201");

    @Test
    void generatedMatchesArePersistedVersionedAndClosedByParticipants() {
        Set<UUID> operational = ConcurrentHashMap.newKeySet();
        operational.addAll(Set.of(DEMAND_ENTERPRISE, SUPPLIER_ENTERPRISE, PROFILE_ONLY_ENTERPRISE));
        EnterpriseLifecycle lifecycle = operational::contains;
        InMemoryEcosystemCatalogStore catalogStore = new InMemoryEcosystemCatalogStore(lifecycle);
        EcosystemCatalogService catalogService = new EcosystemCatalogService(catalogStore, lifecycle);
        ActorScope demandOwner = enterprise(DEMAND_ENTERPRISE);
        ActorScope supplier = enterprise(SUPPLIER_ENTERPRISE);
        ActorScope reviewer = reviewer();

        DemandView demand = catalogService.createDemand(new DemandUpsertRequest(
                "高压燃气阀门采购", "需要零泄漏球阀", List.of("燃气管网"),
                List.of("阀门"), "MEMBERS", null, null, Instant.now().plusSeconds(86400)), demandOwner);
        DemandView submitted = catalogService.submitDemand(demand.id(), demand.version(), demandOwner);
        DemandView opened = catalogService.reviewDemand(
                demand.id(), submitted.version(), new ReviewDecisionRequest(true, null), reviewer);

        OfferingView offering = catalogService.createOffering(new OfferingUpsertRequest(
                "零泄漏高压球阀", "PRODUCT", "适用于高压燃气管网的阀门",
                List.of("燃气管网"), List.of("阀门生产资质"), "MEMBERS"), supplier);
        OfferingView offeringSubmitted = catalogService.submitOffering(
                offering.id(), offering.version(), supplier);
        OfferingView activeOffering = catalogService.reviewOffering(
                offering.id(), offeringSubmitted.version(), new ReviewDecisionRequest(true, null), reviewer);
        assertEquals("ACTIVE", activeOffering.status());

        MemberDirectory directory = new StubMemberDirectory(List.of(
                member(DEMAND_ENTERPRISE, "需求企业", List.of("管线施工"), List.of()),
                member(SUPPLIER_ENTERPRISE, "供应企业", List.of("阀门"), List.of("零泄漏球阀")),
                member(PROFILE_ONLY_ENTERPRISE, "仅有会员档案的企业", List.of("阀门"), List.of("零泄漏球阀"))));
        AiTextService tags = text -> List.of("阀门");
        InMemoryEcosystemMatchStore matchStore = new InMemoryEcosystemMatchStore(lifecycle);
        EcosystemMatchService service = new EcosystemMatchService(
                directory, tags, catalogService, matchStore, catalogStore, lifecycle);

        assertEquals(List.of(), service.persisted(demandOwner));

        List<PersistedMatchView> generated = service.generate(opened.id(), 5, demandOwner);
        assertEquals(1, generated.size());
        assertEquals(SUPPLIER_ENTERPRISE, generated.getFirst().candidateEnterpriseId());
        assertTrue(generated.getFirst().solution().contains("零泄漏高压球阀"));
        assertEquals("PENDING_CONFIRMATION", generated.getFirst().state());
        assertEquals(generated, service.persisted(demandOwner));

        EcosystemMatchService redacting = new EcosystemMatchService(
                directory, tags, catalogService, matchStore, catalogStore, lifecycle,
                (actor, enterpriseId, resourceType, resourceId) ->
                        Optional.of(Set.of("demandTitle", "state")));
        assertEquals(generated, redacting.persisted(demandOwner));
        ActorScope externalPartner = new ActorScope(
                UUID.randomUUID(), "external-partner", "external-partner", UUID.randomUUID(),
                null, Set.of("ASSOCIATION_ADMIN"), Set.of(ASSOCIATION_ID));
        PersistedMatchView redacted = redacting.authorizedMatch(
                generated.getFirst(), externalPartner).orElseThrow();
        assertEquals(generated.getFirst().demandTitle(), redacted.demandTitle());
        assertEquals("PENDING_CONFIRMATION", redacted.state());
        assertTrue(redacted.solution() == null);
        assertTrue(redacted.score() == null);
        assertTrue(redacted.reasons().isEmpty());
        EcosystemMatchService denying = new EcosystemMatchService(
                directory, tags, catalogService, matchStore, catalogStore, lifecycle,
                (actor, enterpriseId, resourceType, resourceId) -> Optional.empty());
        assertTrue(denying.authorizedMatch(generated.getFirst(), externalPartner).isEmpty());

        operational.remove(SUPPLIER_ENTERPRISE);
        assertEquals(List.of(), service.persisted(demandOwner));
        assertEquals(generated, service.persisted(reviewer));
        assertEquals(generated, service.persisted(opened.id(), reviewer));
        assertThrows(PreconditionFailedException.class, () -> service.recommend(
                generated.getFirst().id(), generated.getFirst().version(), reviewer));
        assertTrue(matchStore.recommend(
                generated.getFirst().id(), generated.getFirst().version(), reviewer).isEmpty());
        operational.add(SUPPLIER_ENTERPRISE);
        assertEquals(generated, service.persisted(demandOwner));

        PersistedMatchView supplierConfirmed = service.confirm(
                generated.getFirst().id(), generated.getFirst().version(), supplier);
        PersistedMatchView recommended = service.recommend(
                supplierConfirmed.id(), supplierConfirmed.version(), reviewer);
        assertEquals("PARTIALLY_CONFIRMED", recommended.state());
        assertTrue(recommended.candidateConfirmedAt() != null);
        assertTrue(recommended.demandConfirmedAt() == null);

        PersistedMatchView confirmed = service.confirm(
                recommended.id(), recommended.version(), demandOwner);
        assertEquals("CONFIRMED", confirmed.state());
        assertTrue(confirmed.demandConfirmedAt() != null);
        assertTrue(confirmed.candidateConfirmedAt() != null);

        assertThrows(PreconditionFailedException.class,
                () -> service.confirm(confirmed.id(), confirmed.version(), supplier));

        PersistedMatchView closed = service.close(
                confirmed.id(), confirmed.version(), new MatchCloseRequest("合作终止"), demandOwner);
        assertEquals("CLOSED", closed.state());
        assertEquals("合作终止", closed.closedReason());
        assertEquals("合作终止", redacting.authorizedMatch(closed, demandOwner)
                .orElseThrow().closedReason());
    }

    @Test
    void eitherParticipantAssociationReadsTheCompleteMatchWithoutPartnerPreauthorization() {
        EcosystemCatalogStore catalogStore = mock(EcosystemCatalogStore.class);
        EcosystemMatchStore matchStore = mock(EcosystemMatchStore.class);
        ActorScope actor = reviewerAt(ASSOCIATION_ID);
        Instant updatedAt = Instant.parse("2026-08-30T01:00:00Z");
        PersistedMatchView raw = matchView(
                CROSS_MATCH_ID, 91, MatchLifecycle.CONFIRMED, 7,
                updatedAt.minusSeconds(300), updatedAt.minusSeconds(200),
                updatedAt.minusSeconds(100), "内部关闭原因", updatedAt);
        when(matchStore.list(actor)).thenReturn(List.of(raw));
        when(catalogStore.enterpriseBelongsToAssociation(CROSS_DEMAND_ENTERPRISE, ASSOCIATION_ID))
                .thenReturn(true);
        when(catalogStore.enterpriseBelongsToAssociation(CROSS_SUPPLIER_ENTERPRISE, ASSOCIATION_ID))
                .thenReturn(false);
        List<UUID> authorizationCalls = new ArrayList<>();
        EcosystemMatchService service = isolatedService(
                mock(MemberDirectory.class), text -> List.of(), mock(EcosystemCatalogService.class),
                matchStore, catalogStore, (scope, enterpriseId, type, resourceId) -> {
                    authorizationCalls.add(enterpriseId);
                    return Optional.empty();
                });

        assertEquals(List.of(raw), service.persisted(actor));
        assertTrue(authorizationCalls.isEmpty());
    }

    @Test
    void everyExternalParticipantMustAuthorizeAndHiddenScoreCannotControlOrdering() {
        EcosystemCatalogStore catalogStore = mock(EcosystemCatalogStore.class);
        EcosystemMatchStore matchStore = mock(EcosystemMatchStore.class);
        ActorScope actor = reviewerAt(ASSOCIATION_C);
        UUID lowerId = UUID.fromString("74000000-0000-0000-0000-000000000201");
        UUID higherId = UUID.fromString("74000000-0000-0000-0000-000000000202");
        PersistedMatchView highScore = matchView(
                higherId, 99, MatchLifecycle.PENDING_CONFIRMATION, 0,
                null, null, null, null, Instant.parse("2026-08-30T02:00:00Z"));
        PersistedMatchView lowScore = matchView(
                lowerId, 10, MatchLifecycle.PENDING_CONFIRMATION, 0,
                null, null, null, null, Instant.parse("2026-08-30T01:00:00Z"));
        when(matchStore.list(actor)).thenReturn(List.of(highScore, lowScore));

        EcosystemMatchService missingCandidateConsent = isolatedService(
                mock(MemberDirectory.class), text -> List.of(), mock(EcosystemCatalogService.class),
                matchStore, catalogStore, (scope, enterpriseId, type, resourceId) ->
                        enterpriseId.equals(CROSS_DEMAND_ENTERPRISE)
                                ? Optional.of(Set.of("demandTitle")) : Optional.empty());
        assertTrue(missingCandidateConsent.persisted(actor).isEmpty());

        List<UUID> calls = new ArrayList<>();
        EcosystemMatchService granted = isolatedService(
                mock(MemberDirectory.class), text -> List.of(), mock(EcosystemCatalogService.class),
                matchStore, catalogStore, (scope, enterpriseId, type, resourceId) -> {
                    calls.add(enterpriseId);
                    assertEquals("MATCH", type);
                    assertTrue(Set.of(lowerId, higherId).contains(resourceId));
                    return enterpriseId.equals(CROSS_DEMAND_ENTERPRISE)
                            ? Optional.of(Set.of("demandTitle", "state", "score"))
                            : Optional.of(Set.of("demandTitle", "state"));
                });

        List<PersistedMatchView> values = granted.persisted(actor);
        assertEquals(List.of(lowerId, higherId), values.stream().map(PersistedMatchView::id).toList());
        assertEquals(2, calls.stream().filter(CROSS_DEMAND_ENTERPRISE::equals).count());
        assertEquals(2, calls.stream().filter(CROSS_SUPPLIER_ENTERPRISE::equals).count());
        assertTrue(values.stream().allMatch(value -> value.score() == null));
        assertTrue(values.stream().allMatch(value -> value.updatedAt() == null));
    }

    @Test
    void visibleScoreStillControlsOrderingForParticipantsAndAuthorizedThirdParties() {
        UUID lowerId = UUID.fromString("74000000-0000-0000-0000-000000000201");
        UUID higherId = UUID.fromString("74000000-0000-0000-0000-000000000202");
        PersistedMatchView highScore = matchView(
                higherId, 99, MatchLifecycle.PENDING_CONFIRMATION, 0,
                null, null, null, null, Instant.parse("2026-08-30T02:00:00Z"));
        PersistedMatchView lowScore = matchView(
                lowerId, 10, MatchLifecycle.PENDING_CONFIRMATION, 0,
                null, null, null, null, Instant.parse("2026-08-30T01:00:00Z"));

        ActorScope participant = reviewerAt(ASSOCIATION_ID);
        EcosystemCatalogStore participantCatalog = mock(EcosystemCatalogStore.class);
        EcosystemMatchStore participantMatches = mock(EcosystemMatchStore.class);
        when(participantMatches.list(participant)).thenReturn(List.of(lowScore, highScore));
        when(participantCatalog.enterpriseBelongsToAssociation(
                CROSS_DEMAND_ENTERPRISE, ASSOCIATION_ID)).thenReturn(true);
        EcosystemMatchService participantService = isolatedService(
                mock(MemberDirectory.class), text -> List.of(), mock(EcosystemCatalogService.class),
                participantMatches, participantCatalog, PartnerFieldAuthorization.allowAll());
        assertEquals(
                List.of(higherId, lowerId),
                participantService.persisted(participant).stream().map(PersistedMatchView::id).toList());

        ActorScope thirdParty = reviewerAt(ASSOCIATION_C);
        EcosystemCatalogStore thirdPartyCatalog = mock(EcosystemCatalogStore.class);
        EcosystemMatchStore thirdPartyMatches = mock(EcosystemMatchStore.class);
        when(thirdPartyMatches.list(thirdParty)).thenReturn(List.of(lowScore, highScore));
        EcosystemMatchService authorizedThirdParty = isolatedService(
                mock(MemberDirectory.class), text -> List.of(), mock(EcosystemCatalogService.class),
                thirdPartyMatches, thirdPartyCatalog,
                (scope, enterpriseId, type, resourceId) -> Optional.of(Set.of("score")));
        List<PersistedMatchView> authorized = authorizedThirdParty.persisted(thirdParty);
        assertEquals(
                List.of(higherId, lowerId),
                authorized.stream().map(PersistedMatchView::id).toList());
        assertEquals(List.of(99, 10), authorized.stream().map(PersistedMatchView::score).toList());
    }

    @Test
    void participantGenerateAndMutationResponsesStayCompleteWithoutPreauthorization() {
        EcosystemCatalogStore catalogStore = mock(EcosystemCatalogStore.class);
        EcosystemMatchStore matchStore = mock(EcosystemMatchStore.class);
        EcosystemCatalogService catalogService = mock(EcosystemCatalogService.class);
        MemberDirectory directory = mock(MemberDirectory.class);
        AiTextService tags = mock(AiTextService.class);
        ActorScope demandOwner = enterpriseAt(ASSOCIATION_ID, CROSS_DEMAND_ENTERPRISE);
        ActorScope reviewer = reviewerAt(ASSOCIATION_ID);
        UUID demandId = UUID.fromString("74000000-0000-0000-0000-000000000301");
        DemandView demand = new DemandView(
                demandId, CROSS_DEMAND_ENTERPRISE, "需求企业", "需求标题", "需求说明",
                List.of(), List.of(), "MEMBERS", null, null, null,
                "OPEN", null, 0, false, Instant.parse("2026-08-30T00:00:00Z"));
        PersistedMatchView pending = matchView(
                CROSS_MATCH_ID, 88, MatchLifecycle.PENDING_CONFIRMATION, 0,
                null, null, null, null, Instant.parse("2026-08-30T01:00:00Z"));
        PersistedMatchView recommended = matchView(
                CROSS_MATCH_ID, 88, MatchLifecycle.RECOMMENDED, 1,
                Instant.parse("2026-08-30T01:05:00Z"), null, null, null,
                Instant.parse("2026-08-30T01:05:00Z"));
        PersistedMatchView partiallyConfirmed = matchView(
                CROSS_MATCH_ID, 88, MatchLifecycle.PARTIALLY_CONFIRMED, 1,
                null, Instant.parse("2026-08-30T01:06:00Z"), null, null,
                Instant.parse("2026-08-30T01:06:00Z"));
        PersistedMatchView closed = matchView(
                CROSS_MATCH_ID, 88, MatchLifecycle.CLOSED, 2,
                null, Instant.parse("2026-08-30T01:06:00Z"), null, "不得外泄",
                Instant.parse("2026-08-30T01:07:00Z"));
        when(catalogStore.enterpriseBelongsToAssociation(CROSS_DEMAND_ENTERPRISE, ASSOCIATION_ID))
                .thenReturn(true);
        when(catalogStore.enterpriseBelongsToAssociation(CROSS_SUPPLIER_ENTERPRISE, ASSOCIATION_ID))
                .thenReturn(false);
        when(catalogService.demand(demandId, demandOwner, false)).thenReturn(demand);
        when(catalogService.offerings(demandOwner, null, false, 0, 100))
                .thenReturn(new EcosystemPage<>(List.of(), 0, 0, 100));
        when(directory.findAll(null, demandOwner)).thenReturn(List.of());
        when(tags.extractTags(anyString())).thenReturn(List.of());
        when(matchStore.upsert(eq(demand), anyList(), eq(demandOwner))).thenReturn(List.of(pending));
        when(matchStore.find(CROSS_MATCH_ID, reviewer)).thenReturn(Optional.of(pending));
        when(matchStore.recommend(CROSS_MATCH_ID, 0, reviewer)).thenReturn(Optional.of(recommended));
        when(matchStore.find(CROSS_MATCH_ID, demandOwner))
                .thenReturn(Optional.of(pending), Optional.of(partiallyConfirmed));
        when(matchStore.confirm(CROSS_MATCH_ID, 0, CROSS_DEMAND_ENTERPRISE, demandOwner))
                .thenReturn(Optional.of(partiallyConfirmed));
        when(matchStore.transition(
                CROSS_MATCH_ID, 1, MatchLifecycle.CLOSED, "合作终止", demandOwner))
                .thenReturn(Optional.of(closed));

        List<UUID> authorizationCalls = new ArrayList<>();
        PartnerFieldAuthorization denyPreauthorization = (scope, enterpriseId, type, resourceId) -> {
            authorizationCalls.add(enterpriseId);
            return Optional.empty();
        };
        EcosystemMatchService service = isolatedService(
                directory, tags, catalogService, matchStore, catalogStore, denyPreauthorization);

        assertEquals(pending, service.generate(demandId, 5, demandOwner).getFirst());
        assertEquals(recommended, service.recommend(CROSS_MATCH_ID, 0, reviewer));
        assertEquals(partiallyConfirmed, service.confirm(CROSS_MATCH_ID, 0, demandOwner));
        PersistedMatchView closure = service.close(
                CROSS_MATCH_ID, 1, new MatchCloseRequest("合作终止"), demandOwner);
        assertEquals(closed, closure);
        assertTrue(authorizationCalls.isEmpty());
    }

    @Test
    void nameOnlyMemberProfileCanBeScoredWithoutNullableFieldFailures() {
        MemberProfile nameOnly = new MemberProfile(
                CROSS_SUPPLIER_ENTERPRISE, ASSOCIATION_B, "仅公开名称的企业", null,
                null, null, null, null, null, List.of(), List.of(), List.of(),
                "PARTNERS", "ACTIVE", 0, Instant.now(), Instant.now(), null, null, null);
        MemberDirectory directory = new StubMemberDirectory(List.of(nameOnly));
        EcosystemMatchService service = isolatedService(
                directory, text -> List.of(), mock(EcosystemCatalogService.class),
                mock(EcosystemMatchStore.class), mock(EcosystemCatalogStore.class),
                PartnerFieldAuthorization.allowAll());

        List<EcosystemMatch> matches = service.match(
                new MatchRequest("需求企业", "阀门采购", "燃气管网", null, 5),
                enterpriseAt(ASSOCIATION_ID, CROSS_DEMAND_ENTERPRISE));

        assertEquals(1, matches.size());
        assertEquals("仅公开名称的企业", matches.getFirst().supplierCompany());
    }

    private static EcosystemMatchService isolatedService(
            MemberDirectory directory,
            AiTextService tags,
            EcosystemCatalogService catalogService,
            EcosystemMatchStore matchStore,
            EcosystemCatalogStore catalogStore,
            PartnerFieldAuthorization authorization) {
        return new EcosystemMatchService(
                directory, tags, catalogService, matchStore, catalogStore,
                enterpriseId -> true, authorization);
    }

    private static PersistedMatchView matchView(
            UUID id,
            int score,
            String state,
            long version,
            Instant recommendedAt,
            Instant demandConfirmedAt,
            Instant candidateConfirmedAt,
            String closedReason,
            Instant updatedAt) {
        return new PersistedMatchView(
                id,
                UUID.fromString("74000000-0000-0000-0000-000000000301"),
                CROSS_DEMAND_ENTERPRISE,
                CROSS_SUPPLIER_ENTERPRISE,
                "需求企业",
                "需求标题",
                "燃气管网",
                "供应企业",
                "解决方案",
                score,
                List.of("内部原因"),
                state,
                recommendedAt,
                demandConfirmedAt,
                candidateConfirmedAt,
                closedReason,
                version,
                updatedAt);
    }

    private static ActorScope enterpriseAt(UUID associationId, UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + enterpriseId, "enterprise",
                associationId, enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope reviewerAt(UUID associationId) {
        return new ActorScope(
                UUID.randomUUID(), "reviewer-" + associationId, "reviewer",
                associationId, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }

    private static ActorScope enterprise(UUID enterpriseId) {
        return enterpriseAt(ASSOCIATION_ID, enterpriseId);
    }

    private static ActorScope reviewer() {
        return reviewerAt(ASSOCIATION_ID);
    }

    private static MemberProfile member(
            UUID id, String name, List<String> capabilities, List<String> products) {
        Instant now = Instant.now();
        return new MemberProfile(
                id, ASSOCIATION_ID, name, null, "制造",
                "北京市", null, null, name + "简介", capabilities, products,
                List.of(), "MEMBERS", "ACTIVE", 0, now, now, null, null, null);
    }

    private record StubMemberDirectory(List<MemberProfile> members) implements MemberDirectory {
        @Override
        public List<MemberProfile> findAll(String query, ActorScope actor) {
            return members;
        }

        @Override
        public Optional<MemberProfile> findById(UUID id, ActorScope actor) {
            return members.stream().filter(member -> member.id().equals(id)).findFirst();
        }
    }
}
