package com.guanxian.platform.iam;

import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class CrossAssociationFieldAuthorizationService implements PartnerFieldAuthorization {
    private final CrossAssociationStore store;

    CrossAssociationFieldAuthorizationService(CrossAssociationStore store) {
        this.store = store;
    }

    @Override
    public Optional<Set<String>> authorizedFields(
            ActorScope actor, UUID enterpriseId, String resourceType, UUID resourceId) {
        if (actor == null || enterpriseId == null || resourceType == null || resourceId == null) {
            return Optional.empty();
        }
        String normalizedType = resourceType.trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = PartnerFieldAuthorization.allowedFields(normalizedType);
        if (allowed.isEmpty()) {
            return Optional.empty();
        }
        UUID sourceAssociationId = store.enterpriseAssociation(enterpriseId).orElse(null);
        if (sourceAssociationId == null) {
            return Optional.empty();
        }
        if (!store.resourceOwnedByEnterprise(normalizedType, resourceId, enterpriseId)) {
            return Optional.empty();
        }
        if (actor.isSystemAdmin()) {
            if (actor.enterpriseId() != null && !actor.enterpriseId().equals(enterpriseId)) {
                return Optional.empty();
            }
            if (actor.associationId() == null) {
                return actor.enterpriseId() == null ? Optional.of(allowed) : Optional.empty();
            }
        }
        if (actor.associationId() != null && actor.associationId().equals(sourceAssociationId)) {
            return Optional.of(allowed);
        }
        if (actor.associationId() == null
                || !actor.partnerAssociationIds().contains(sourceAssociationId)) {
            return Optional.empty();
        }
        return store.authorizedFields(
                        actor.associationId(), enterpriseId, normalizedType, resourceId, Instant.now())
                .filter(fields -> !fields.isEmpty())
                .filter(allowed::containsAll)
                .filter(fields -> fields.containsAll(
                        PartnerFieldAuthorization.requiredFields(normalizedType)))
                .map(Set::copyOf);
    }
}
