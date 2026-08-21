package com.guanxian.platform.shared.security;

import java.util.Set;
import java.util.UUID;

public record ActorScope(
        UUID userId,
        String subject,
        String username,
        UUID associationId,
        UUID enterpriseId,
        Set<String> roles,
        Set<UUID> partnerAssociationIds) {

    public ActorScope {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        partnerAssociationIds = partnerAssociationIds == null ? Set.of() : Set.copyOf(partnerAssociationIds);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isSystemAdmin() {
        return hasRole("SYSTEM_ADMIN");
    }

    public boolean isAssociationStaff() {
        return hasRole("ASSOCIATION_ADMIN") || hasRole("ASSOCIATION_OPERATOR");
    }

    public boolean isAssociationReviewer() {
        return hasRole("ASSOCIATION_ADMIN");
    }

    public boolean isEnterpriseAdmin() {
        return hasRole("ENTERPRISE_ADMIN");
    }
}
