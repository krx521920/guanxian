package com.guanxian.platform.ecosystem;

import com.guanxian.platform.ai.AiTextService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcosystemMatchServiceTest {
    private static final UUID ASSOCIATION_ID = UUID.randomUUID();
    private static final UUID DEMAND_ENTERPRISE = UUID.randomUUID();
    private static final UUID SUPPLIER_ENTERPRISE = UUID.randomUUID();

    @Test
    void generatedMatchesArePersistedVersionedAndClosedByParticipants() {
        InMemoryEcosystemCatalogStore catalogStore = new InMemoryEcosystemCatalogStore();
        EcosystemCatalogService catalogService = new EcosystemCatalogService(catalogStore);
        ActorScope demandOwner = enterprise(DEMAND_ENTERPRISE);
        ActorScope supplier = enterprise(SUPPLIER_ENTERPRISE);
        ActorScope reviewer = reviewer();

        DemandView demand = catalogService.createDemand(new DemandUpsertRequest(
                "高压燃气阀门采购", "需要零泄漏球阀", List.of("燃气管网"),
                List.of("阀门"), "MEMBERS", null, null, Instant.now().plusSeconds(86400)), demandOwner);
        DemandView submitted = catalogService.submitDemand(demand.id(), demand.version(), demandOwner);
        DemandView opened = catalogService.reviewDemand(
                demand.id(), submitted.version(), new ReviewDecisionRequest(true, null), reviewer);

        MemberDirectory directory = new StubMemberDirectory(List.of(
                member(DEMAND_ENTERPRISE, "需求企业", List.of("管线施工"), List.of()),
                member(SUPPLIER_ENTERPRISE, "供应企业", List.of("阀门"), List.of("零泄漏球阀"))));
        AiTextService tags = text -> List.of("阀门");
        InMemoryEcosystemMatchStore matchStore = new InMemoryEcosystemMatchStore();
        EcosystemMatchService service = new EcosystemMatchService(
                directory, tags, catalogService, matchStore, catalogStore);

        assertTrue(service.list(demandOwner).isEmpty(),
                "an empty match repository must not be filled with demo records");

        List<PersistedMatchView> generated = service.generate(opened.id(), 5, demandOwner);
        assertEquals(1, generated.size());
        assertEquals(SUPPLIER_ENTERPRISE, generated.getFirst().candidateEnterpriseId());
        assertEquals("PENDING_CONFIRMATION", generated.getFirst().state());
        assertEquals(generated, service.list(demandOwner));
        assertEquals(generated, service.list(supplier));
        assertEquals(generated, service.list(reviewer));
        assertEquals(generated, service.list(system()));
        assertTrue(service.list(enterprise(UUID.randomUUID())).isEmpty());
        assertTrue(service.list(reviewer(UUID.randomUUID())).isEmpty());

        PersistedMatchView confirmed = service.confirm(
                generated.getFirst().id(), generated.getFirst().version(), supplier);
        PersistedMatchView recommended = service.recommend(
                confirmed.id(), confirmed.version(), reviewer);
        assertEquals("RECOMMENDED", recommended.state());

        assertThrows(PreconditionFailedException.class,
                () -> service.confirm(recommended.id(), confirmed.version(), supplier));

        PersistedMatchView closed = service.close(
                recommended.id(), recommended.version(), new MatchCloseRequest("合作已完成"), demandOwner);
        assertEquals("CLOSED", closed.state());
        assertEquals("合作已完成", closed.closedReason());
    }

    private static ActorScope enterprise(UUID enterpriseId) {
        return new ActorScope(
                UUID.randomUUID(), "subject-" + enterpriseId, "enterprise",
                ASSOCIATION_ID, enterpriseId, Set.of("ENTERPRISE_ADMIN"), Set.of());
    }

    private static ActorScope reviewer() {
        return reviewer(ASSOCIATION_ID);
    }

    private static ActorScope reviewer(UUID associationId) {
        return new ActorScope(
                UUID.randomUUID(), "reviewer", "reviewer",
                associationId, null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    }

    private static ActorScope system() {
        return new ActorScope(
                UUID.randomUUID(), "system", "system",
                null, null, Set.of("SYSTEM_ADMIN"), Set.of());
    }

    private static MemberProfile member(
            UUID id, String name, List<String> capabilities, List<String> products) {
        Instant now = Instant.now();
        return new MemberProfile(
                id, ASSOCIATION_ID, name, null, "制造",
                "北京市", null, null, name + "简介", capabilities, products,
                List.of(), "MEMBERS", "ACTIVE", 0, now, now);
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
