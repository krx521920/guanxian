package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberPartnerFieldAuthorizationTest {
    private static final UUID SOURCE_ASSOCIATION =
            UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ASSOCIATION =
            UUID.fromString("72000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000101");

    @Test
    void partnerListAndDetailReturnOnlyExplicitlyAuthorizedBusinessFields() {
        InMemoryMemberRepository repository = repositoryWith(member("ACTIVE"));
        AtomicInteger calls = new AtomicInteger();
        PartnerFieldAuthorization authorization = (actor, enterpriseId, resourceType, resourceId) -> {
            calls.incrementAndGet();
            assertEquals(MEMBER_ID, enterpriseId);
            assertEquals("MEMBER", resourceType);
            assertEquals(MEMBER_ID, resourceId);
            return Optional.of(Set.of(
                    "name", "category", "products",
                    "unifiedSocialCreditCode", "contactName", "contactPhone", "unknownField"));
        };
        MemberService service = service(repository, authorization);

        MemberProfile listItem = service.findAll(null, partnerActor()).getFirst();
        MemberProfile detail = service.get(MEMBER_ID, partnerActor());

        assertEquals(2, calls.get());
        assertEquals("管线企业", listItem.name());
        assertEquals("智慧管网", listItem.category());
        assertEquals(List.of("监测平台"), listItem.products());
        assertNull(listItem.unifiedSocialCreditCode());
        assertNull(listItem.contactName());
        assertNull(listItem.contactPhone());
        assertNull(listItem.address());
        assertNull(listItem.introduction());
        assertTrue(listItem.capabilities().isEmpty());
        assertTrue(listItem.cooperationNeeds().isEmpty());
        assertEquals(listItem, detail);
    }

    @Test
    void deniedOrMalformedAuthorizationHidesTheMemberAndCannotBeInferredBySearchOrPageSize() {
        InMemoryMemberRepository repository = repositoryWith(member("ACTIVE"));
        PartnerFieldAuthorization denied = (actor, enterpriseId, resourceType, resourceId) -> Optional.empty();
        MemberService deniedService = service(repository, denied);

        assertTrue(deniedService.findAll(null, partnerActor()).isEmpty());
        assertTrue(deniedService.page(null, null, false, partnerActor(), 0, 20).isEmpty());
        assertTrue(deniedService.findById(MEMBER_ID, partnerActor()).isEmpty());

        PartnerFieldAuthorization missingAnchor =
                (actor, enterpriseId, resourceType, resourceId) -> Optional.of(Set.of("introduction"));
        MemberService malformedService = service(repository, missingAnchor);
        assertTrue(malformedService.findAll(null, partnerActor()).isEmpty());

        PartnerFieldAuthorization nameOnly =
                (actor, enterpriseId, resourceType, resourceId) -> Optional.of(Set.of("name"));
        MemberService nameOnlyService = service(repository, nameOnly);
        assertTrue(nameOnlyService.findAll("仅授权方不可见的秘密", partnerActor()).isEmpty());
        assertEquals(List.of(MEMBER_ID), nameOnlyService.findAll("管线企业", partnerActor()).stream()
                .map(MemberProfile::id)
                .toList());
    }

    @Test
    void sameAssociationAndSystemAdministratorRemainCompleteWithoutPartnerAuthorization() {
        InMemoryMemberRepository repository = repositoryWith(member("ACTIVE"));
        AtomicInteger calls = new AtomicInteger();
        PartnerFieldAuthorization denyAndCount = (actor, enterpriseId, resourceType, resourceId) -> {
            calls.incrementAndGet();
            return Optional.empty();
        };
        MemberService service = service(repository, denyAndCount);

        MemberProfile sameAssociation = service.get(MEMBER_ID, sameAssociationActor());
        MemberProfile system = service.get(MEMBER_ID, systemActor());

        assertEquals(0, calls.get());
        assertEquals("91110000MEMBER001", sameAssociation.unifiedSocialCreditCode());
        assertEquals("张工", sameAssociation.contactName());
        assertEquals("13800000001", sameAssociation.contactPhone());
        assertEquals(sameAssociation, system);
    }

    @Test
    void inactivePartnerMemberStaysHiddenEvenWhenFieldsAreAuthorized() {
        InMemoryMemberRepository repository = repositoryWith(member("PENDING_REVIEW"));
        PartnerFieldAuthorization authorization =
                (actor, enterpriseId, resourceType, resourceId) -> Optional.of(Set.of("name"));
        MemberService service = service(repository, authorization);

        assertTrue(service.findAll(null, partnerActor()).isEmpty());
        assertTrue(service.findById(MEMBER_ID, partnerActor()).isEmpty());
    }

    private static MemberService service(
            InMemoryMemberRepository repository, PartnerFieldAuthorization authorization) {
        return new MemberService(repository, new InMemoryAuditTrail(), authorization);
    }

    private static InMemoryMemberRepository repositoryWith(MemberProfile member) {
        InMemoryMemberRepository repository = new InMemoryMemberRepository(SOURCE_ASSOCIATION);
        repository.insert(member);
        return repository;
    }

    private static MemberProfile member(String status) {
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        return new MemberProfile(
                MEMBER_ID,
                SOURCE_ASSOCIATION,
                "管线企业",
                "91110000MEMBER001",
                "智慧管网",
                "北京市海淀区",
                "张工",
                "13800000001",
                "仅授权方不可见的秘密",
                List.of("泄漏监测"),
                List.of("监测平台"),
                List.of("寻找合作方"),
                "PARTNERS",
                status,
                3,
                now,
                now,
                null,
                null,
                null);
    }

    private static ActorScope partnerActor() {
        return new ActorScope(
                null, "partner-user", "partner-user", TARGET_ASSOCIATION, null,
                Set.of("ENTERPRISE_MEMBER"), Set.of(SOURCE_ASSOCIATION));
    }

    private static ActorScope sameAssociationActor() {
        return new ActorScope(
                null, "member-user", "member-user", SOURCE_ASSOCIATION, null,
                Set.of("ENTERPRISE_MEMBER"), Set.of());
    }

    private static ActorScope systemActor() {
        return new ActorScope(
                null, "system-admin", "system-admin", null, null,
                Set.of("SYSTEM_ADMIN"), Set.of());
    }
}
