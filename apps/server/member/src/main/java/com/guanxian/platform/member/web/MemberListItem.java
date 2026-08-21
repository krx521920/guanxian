package com.guanxian.platform.member.web;

import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.security.ActorScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberListItem(
        UUID id,
        String name,
        String shortName,
        String role,
        List<String> scenes,
        List<String> products,
        String city,
        String contact,
        int completeness,
        String status,
        String visibility,
        boolean canEdit,
        boolean canReview,
        Instant updatedAt) {

    static MemberListItem from(MemberProfile member, ActorScope actor, MemberService service) {
        int populated = 3;
        populated += member.address() == null ? 0 : 1;
        populated += member.contactName() == null ? 0 : 1;
        populated += member.introduction() == null ? 0 : 1;
        populated += member.capabilities().isEmpty() ? 0 : 1;
        populated += member.products().isEmpty() ? 0 : 1;
        populated += member.cooperationNeeds().isEmpty() ? 0 : 1;
        int completeness = Math.min(100, Math.round(populated * 100f / 9));
        return new MemberListItem(
                member.id(), member.name(), abbreviate(member.name()), member.category(), member.capabilities(),
                member.products(), member.address(), member.contactName(), completeness,
                switch (member.status()) {
                    case "PENDING_REVIEW" -> "待审核";
                    case "INCOMPLETE" -> "待完善";
                    case "DISABLED" -> "已停用";
                    default -> "已认证";
                },
                member.visibility(), service.canEdit(actor, member), service.canReview(actor, member), member.updatedAt());
    }

    private static String abbreviate(String name) {
        return name.length() <= 10 ? name : name.substring(0, 10);
    }
}
