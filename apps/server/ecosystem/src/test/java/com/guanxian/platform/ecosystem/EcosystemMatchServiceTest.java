package com.guanxian.platform.ecosystem;

import com.guanxian.platform.ai.AiTextService;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcosystemMatchServiceTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID DEMAND_ENTERPRISE = UUID.randomUUID();
    private static final UUID SUPPLIER_ENTERPRISE = UUID.randomUUID();
    private static final UUID PROFILE_ONLY_ENTERPRISE = UUID.randomUUID();

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
    }

    private static ActorScope enterprise(UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + enterpriseId, "enterprise",
                ASSOCIATION_ID, enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope reviewer() {
        return new ActorScope(
                UUID.randomUUID(), "reviewer", "reviewer",
                ASSOCIATION_ID, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
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
