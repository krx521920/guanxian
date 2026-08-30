package com.guanxian.platform.ecosystem;

import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.http.HttpStatus;

import java.util.UUID;

final class EcosystemScopeGuard {
    private EcosystemScopeGuard() {
    }

    static void requireWriteContext(ActorScope actor) {
        if (actor.isSystemAdmin() && actor.associationId() == null) {
            throw new ApiException(
                    "ASSOCIATION_CONTEXT_REQUIRED",
                    "system administrators must select an association context",
                    HttpStatus.BAD_REQUEST);
        }
    }

    static boolean systemCanReadEnterprise(
            ActorScope actor, UUID enterpriseId, EcosystemCatalogStore catalogStore) {
        if (!actor.isSystemAdmin()) {
            return false;
        }
        if (actor.associationId() == null) {
            return actor.enterpriseId() == null;
        }
        if (!catalogStore.enterpriseBelongsToAssociation(enterpriseId, actor.associationId())) {
            return false;
        }
        return actor.enterpriseId() == null || actor.enterpriseId().equals(enterpriseId);
    }

    static boolean systemCanReadMatch(
            ActorScope actor, PersistedMatchView match, EcosystemCatalogStore catalogStore) {
        if (!actor.isSystemAdmin()) {
            return false;
        }
        if (actor.associationId() == null) {
            return actor.enterpriseId() == null;
        }
        if (!catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId())) {
            return false;
        }
        return actor.enterpriseId() == null
                || actor.enterpriseId().equals(match.demandEnterpriseId())
                || actor.enterpriseId().equals(match.candidateEnterpriseId());
    }

    static void requireSystemEnterpriseWrite(
            ActorScope actor, UUID enterpriseId, EcosystemCatalogStore catalogStore) {
        requireWriteContext(actor);
        if (actor.isSystemAdmin()
                && !systemCanReadEnterprise(actor, enterpriseId, catalogStore)) {
            throw new ForbiddenException(
                    "ENTERPRISE_SCOPE_VIOLATION",
                    "resource is outside the selected system context");
        }
    }

    static void requireSystemMatchWrite(
            ActorScope actor, PersistedMatchView match, EcosystemCatalogStore catalogStore) {
        requireWriteContext(actor);
        if (actor.isSystemAdmin() && !systemCanReadMatch(actor, match, catalogStore)) {
            throw new ForbiddenException(
                    "MATCH_SCOPE_VIOLATION",
                    "match is outside the selected system context");
        }
    }
}
