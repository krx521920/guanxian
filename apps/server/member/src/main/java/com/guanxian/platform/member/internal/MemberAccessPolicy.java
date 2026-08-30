package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.security.ActorScope;

final class MemberAccessPolicy {
    private MemberAccessPolicy() {
    }

    static boolean canRead(ActorScope actor, MemberProfile member) {
        if (actor.isSystemAdmin()) {
            return withinSelectedSystemContext(actor, member);
        }
        if (member.id().equals(actor.enterpriseId())) {
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
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null && actor.enterpriseId() == null;
        }
        return actor.isAssociationStaff() && actor.enterpriseId() == null;
    }

    static boolean canUpdate(ActorScope actor, MemberProfile member) {
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null && withinSelectedSystemContext(actor, member);
        }
        if (actor.isAssociationStaff() && member.associationId().equals(actor.associationId())) {
            return true;
        }
        return actor.isEnterpriseAdmin() && member.id().equals(actor.enterpriseId());
    }

    static boolean canReview(ActorScope actor, MemberProfile member) {
        return (actor.isSystemAdmin()
                && actor.associationId() != null
                && withinSelectedSystemContext(actor, member))
                || (actor.isAssociationReviewer() && member.associationId().equals(actor.associationId()));
    }

    static boolean canDelete(ActorScope actor, MemberProfile member) {
        return canReview(actor, member);
    }

    private static boolean withinSelectedSystemContext(ActorScope actor, MemberProfile member) {
        if (actor.enterpriseId() != null && !actor.enterpriseId().equals(member.id())) {
            return false;
        }
        return actor.associationId() == null || actor.associationId().equals(member.associationId());
    }
}
