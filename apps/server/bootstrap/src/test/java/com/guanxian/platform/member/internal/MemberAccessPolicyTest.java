package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberAccessPolicyTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID OTHER_ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void writeAndReviewDecisionsEnforceRoleAssociationAndEnterpriseOwnership() {
        MemberProfile ownMember = member(ENTERPRISE, ASSOCIATION, "MEMBERS");
        MemberProfile otherMember = member(OTHER_ENTERPRISE, ASSOCIATION, "MEMBERS");
        MemberProfile foreignMember = member(OTHER_ENTERPRISE, OTHER_ASSOCIATION, "MEMBERS");
        ActorScope enterpriseAdmin = actor(ASSOCIATION, ENTERPRISE, "ENTERPRISE_ADMIN", Set.of());
        ActorScope operator = actor(ASSOCIATION, null, "ASSOCIATION_OPERATOR", Set.of());
        ActorScope associationAdmin = actor(ASSOCIATION, null, "ASSOCIATION_ADMIN", Set.of());
        ActorScope systemAdmin = actor(null, null, "SYSTEM_ADMIN", Set.of());

        assertFalse(MemberAccessPolicy.canCreate(enterpriseAdmin));
        assertTrue(MemberAccessPolicy.canCreate(operator));
        assertTrue(MemberAccessPolicy.canCreate(associationAdmin));
        assertTrue(MemberAccessPolicy.canCreate(systemAdmin));

        assertTrue(MemberAccessPolicy.canUpdate(enterpriseAdmin, ownMember));
        assertFalse(MemberAccessPolicy.canUpdate(enterpriseAdmin, otherMember));
        assertTrue(MemberAccessPolicy.canUpdate(operator, ownMember));
        assertFalse(MemberAccessPolicy.canUpdate(operator, foreignMember));

        assertFalse(MemberAccessPolicy.canReview(enterpriseAdmin, ownMember));
        assertFalse(MemberAccessPolicy.canDelete(enterpriseAdmin, ownMember));
        assertFalse(MemberAccessPolicy.canReview(operator, ownMember));
        assertFalse(MemberAccessPolicy.canDelete(operator, ownMember));
        assertTrue(MemberAccessPolicy.canReview(associationAdmin, ownMember));
        assertTrue(MemberAccessPolicy.canDelete(associationAdmin, ownMember));
        assertFalse(MemberAccessPolicy.canReview(associationAdmin, foreignMember));
        assertFalse(MemberAccessPolicy.canDelete(associationAdmin, foreignMember));
        assertTrue(MemberAccessPolicy.canReview(systemAdmin, foreignMember));
        assertTrue(MemberAccessPolicy.canDelete(systemAdmin, foreignMember));
    }

    @Test
    void readDecisionsHonorPrivateAssociationPartnerAndMemberVisibility() {
        ActorScope ownEnterprise = actor(ASSOCIATION, ENTERPRISE, "ENTERPRISE_MEMBER", Set.of());
        ActorScope sameAssociation = actor(ASSOCIATION, OTHER_ENTERPRISE, "ENTERPRISE_MEMBER", Set.of());
        ActorScope partner = actor(OTHER_ASSOCIATION, OTHER_ENTERPRISE, "ENTERPRISE_MEMBER", Set.of(ASSOCIATION));
        ActorScope unrelated = actor(OTHER_ASSOCIATION, OTHER_ENTERPRISE, "ENTERPRISE_MEMBER", Set.of());
        ActorScope staff = actor(ASSOCIATION, null, "ASSOCIATION_OPERATOR", Set.of());

        assertTrue(MemberAccessPolicy.canRead(ownEnterprise, member(ENTERPRISE, ASSOCIATION, "PRIVATE")));
        assertFalse(MemberAccessPolicy.canRead(sameAssociation, member(ENTERPRISE, ASSOCIATION, "PRIVATE")));
        assertTrue(MemberAccessPolicy.canRead(staff, member(ENTERPRISE, ASSOCIATION, "PRIVATE")));
        assertTrue(MemberAccessPolicy.canRead(sameAssociation, member(ENTERPRISE, ASSOCIATION, "ASSOCIATION")));
        assertFalse(MemberAccessPolicy.canRead(unrelated, member(ENTERPRISE, ASSOCIATION, "ASSOCIATION")));
        assertTrue(MemberAccessPolicy.canRead(partner, member(ENTERPRISE, ASSOCIATION, "PARTNERS")));
        assertFalse(MemberAccessPolicy.canRead(unrelated, member(ENTERPRISE, ASSOCIATION, "PARTNERS")));
        assertTrue(MemberAccessPolicy.canRead(unrelated, member(ENTERPRISE, ASSOCIATION, "MEMBERS")));
        assertFalse(MemberAccessPolicy.canRead(actor(null, null, "ENTERPRISE_MEMBER", Set.of()),
                member(ENTERPRISE, ASSOCIATION, "MEMBERS")));
        assertFalse(MemberAccessPolicy.canRead(unrelated, member(ENTERPRISE, ASSOCIATION, "UNKNOWN")));
    }

    private static ActorScope actor(
            UUID associationId, UUID enterpriseId, String role, Set<UUID> partners) {
        return new ActorScope(UUID.randomUUID(), role, role, associationId, enterpriseId, Set.of(role), partners);
    }

    private static MemberProfile member(UUID id, UUID associationId, String visibility) {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        return new MemberProfile(id, associationId, "会员企业", null, "技术服务单位", null, null, null,
                null, List.of(), List.of(), List.of(), visibility, "ACTIVE", 0, now, now);
    }
}