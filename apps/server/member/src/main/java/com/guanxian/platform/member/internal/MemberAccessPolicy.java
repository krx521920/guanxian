package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.security.ActorScope;

final class MemberAccessPolicy {
    private MemberAccessPolicy() {
    }

    static boolean canRead(ActorScope actor, MemberProfile member) {
        if (actor.isSystemAdmin() || member.id().equals(actor.enterpriseId())) {
            return true;
        }
        boolean sameAssociation = member.associationId().equals(actor.associationId());
        if (sameAssociation && actor.isAssociationStaff()) {
            return true;
        }
        return switch (member.visibility()) {
            case "PRIVATE" -> false;
            case "ASSOCIATION" -> sameAssociation;
            case "PARTNERS" -> sameAssociation || actor.partnerAssociationIds().contains(member.associationId());
            case "MEMBERS", "PUBLIC" -> actor.associationId() != null;
            default -> false;
        };
    }

    static boolean canCreate(ActorScope actor) {
        return actor.isSystemAdmin() || actor.isAssociationStaff();
    }

    static boolean canUpdate(ActorScope actor, MemberProfile member) {
        if (actor.isSystemAdmin()) {
            return true;
        }
        if (actor.isAssociationStaff() && member.associationId().equals(actor.associationId())) {
            return true;
        }
        return actor.isEnterpriseAdmin() && member.id().equals(actor.enterpriseId());
    }

    static boolean canReview(ActorScope actor, MemberProfile member) {
        return actor.isSystemAdmin()
                || (actor.isAssociationReviewer() && member.associationId().equals(actor.associationId()));
    }

    static boolean canDelete(ActorScope actor, MemberProfile member) {
        return canReview(actor, member);
    }
}
