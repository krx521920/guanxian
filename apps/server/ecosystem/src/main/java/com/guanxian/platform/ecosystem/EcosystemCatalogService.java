package com.guanxian.platform.ecosystem;

import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class EcosystemCatalogService {
    private static final Set<String> EDITABLE_OFFERING_STATES = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE_DEMAND_STATES = Set.of("DRAFT", "REJECTED");
    private final EcosystemCatalogStore store;
    private final EnterpriseLifecycle enterpriseLifecycle;
    private final PartnerFieldAuthorization partnerFields;

    @Autowired
    public EcosystemCatalogService(
            EcosystemCatalogStore store,
            EnterpriseLifecycle enterpriseLifecycle,
            PartnerFieldAuthorization partnerFields) {
        this.store = store;
        this.enterpriseLifecycle = enterpriseLifecycle;
        this.partnerFields = partnerFields;
    }

    public EcosystemCatalogService(
            EcosystemCatalogStore store,
            EnterpriseLifecycle enterpriseLifecycle) {
        this(store, enterpriseLifecycle, PartnerFieldAuthorization.allowAll());
    }

    EcosystemCatalogService(EcosystemCatalogStore store) {
        this(store, enterpriseId -> true);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EcosystemPage<OfferingView> offerings(
            ActorScope actor, String query, boolean includeDeleted, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) safePage * safeSize;
        boolean allowedDeleted = includeDeleted && canReadDeleted(actor);
        return new EcosystemPage<>(
                store.listOfferings(actor, query, allowedDeleted, offset, safeSize).stream()
                        .map(item -> authorizedOffering(item, actor))
                        .flatMap(Optional::stream)
                        .toList(),
                store.countOfferings(actor, query, allowedDeleted),
                safePage,
                safeSize);
    }

    @Transactional(readOnly = true)
    public OfferingView offering(UUID id, ActorScope actor, boolean includeDeleted) {
        return store.findOffering(id, actor, includeDeleted && canReadDeleted(actor))
                .flatMap(item -> authorizedOffering(item, actor))
                .orElseThrow(() -> new NotFoundException("offering", id));
    }

    @Transactional
    public OfferingView createOffering(OfferingUpsertRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        UUID enterpriseId = requireEnterprise(actor);
        requireOperational(enterpriseId);
        OfferingView created = store.createOffering(enterpriseId, request, actor);
        record(actor, "CREATE", "PRODUCT_SERVICE", created.id(), created.enterpriseId(), created.version(), created);
        return created;
    }

    @Transactional
    public OfferingView updateOffering(
            UUID id, long expectedVersion, OfferingUpsertRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        OfferingView current = offering(id, actor, false);
        requireOwner(actor, current.enterpriseId());
        requireState(current.status(), EDITABLE_OFFERING_STATES, "offering must be DRAFT or REJECTED to edit");
        requireVersion(current.version(), expectedVersion);
        OfferingView updated = store.updateOffering(id, expectedVersion, request, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "UPDATE", "PRODUCT_SERVICE", updated.id(), updated.enterpriseId(), updated.version(), updated);
        return updated;
    }

    @Transactional
    public OfferingView submitOffering(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        OfferingView current = offering(id, actor, false);
        requireOwner(actor, current.enterpriseId());
        requireState(current.status(), EDITABLE_OFFERING_STATES, "only a draft or rejected offering can be submitted");
        return transitionOffering(current, expectedVersion, "PENDING_REVIEW", "SUBMIT", actor);
    }

    @Transactional
    public OfferingView reviewOffering(
            UUID id, long expectedVersion, ReviewDecisionRequest decision, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        requireReviewer(actor);
        OfferingView current = offering(id, actor, false);
        requireReviewAssociation(actor, current.enterpriseId());
        requireState(current.status(), Set.of("PENDING_REVIEW"), "offering is not pending review");
        return transitionOffering(
                current, expectedVersion, decision.approved() ? "ACTIVE" : "REJECTED",
                decision.approved() ? "APPROVE" : "REJECT", actor);
    }

    @Transactional
    public OfferingView disableOffering(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        OfferingView current = offering(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        if ("DISABLED".equals(current.status())) {
            throw new PreconditionFailedException("offering is already disabled");
        }
        return transitionOffering(current, expectedVersion, "DISABLED", "DISABLE", actor);
    }

    @Transactional
    public OfferingView deleteOffering(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        OfferingView current = offering(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        OfferingView deleted = store.softDeleteOffering(id, expectedVersion, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "SOFT_DELETE", "PRODUCT_SERVICE", deleted.id(), deleted.enterpriseId(),
                deleted.version(), deleted);
        return deleted;
    }

    @Transactional
    public OfferingView restoreOffering(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        OfferingView current = offering(id, actor, true);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        OfferingView restored = store.restoreOffering(id, expectedVersion, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "RESTORE", "PRODUCT_SERVICE", restored.id(), restored.enterpriseId(),
                restored.version(), restored);
        return restored;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EcosystemPage<DemandView> demands(
            ActorScope actor, String query, boolean includeDeleted, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) safePage * safeSize;
        boolean allowedDeleted = includeDeleted && canReadDeleted(actor);
        return new EcosystemPage<>(
                store.listDemands(actor, query, allowedDeleted, offset, safeSize).stream()
                        .map(item -> authorizedDemand(item, actor))
                        .flatMap(Optional::stream)
                        .toList(),
                store.countDemands(actor, query, allowedDeleted),
                safePage,
                safeSize);
    }

    @Transactional(readOnly = true)
    public DemandView demand(UUID id, ActorScope actor, boolean includeDeleted) {
        return store.findDemand(id, actor, includeDeleted && canReadDeleted(actor))
                .flatMap(item -> authorizedDemand(item, actor))
                .orElseThrow(() -> new NotFoundException("demand", id));
    }

    @Transactional
    public DemandView createDemand(DemandUpsertRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        validateBudget(request.budgetMin(), request.budgetMax());
        UUID enterpriseId = requireEnterprise(actor);
        requireOperational(enterpriseId);
        DemandView created = store.createDemand(enterpriseId, request, actor);
        record(actor, "CREATE", "COOPERATION_DEMAND", created.id(), created.enterpriseId(), created.version(), created);
        return created;
    }

    @Transactional
    public DemandView updateDemand(
            UUID id, long expectedVersion, DemandUpsertRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        validateBudget(request.budgetMin(), request.budgetMax());
        DemandView current = demand(id, actor, false);
        requireOwner(actor, current.enterpriseId());
        requireState(current.status(), EDITABLE_DEMAND_STATES, "demand must be DRAFT or REJECTED to edit");
        requireVersion(current.version(), expectedVersion);
        DemandView updated = store.updateDemand(id, expectedVersion, request, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "UPDATE", "COOPERATION_DEMAND", updated.id(), updated.enterpriseId(),
                updated.version(), updated);
        return updated;
    }

    @Transactional
    public DemandView submitDemand(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView current = demand(id, actor, false);
        requireOwner(actor, current.enterpriseId());
        requireState(current.status(), EDITABLE_DEMAND_STATES, "only a draft or rejected demand can be submitted");
        return transitionDemand(current, expectedVersion, "PENDING_REVIEW", null, "SUBMIT", actor);
    }

    @Transactional
    public DemandView reviewDemand(
            UUID id, long expectedVersion, ReviewDecisionRequest decision, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        requireReviewer(actor);
        DemandView current = demand(id, actor, false);
        requireReviewAssociation(actor, current.enterpriseId());
        requireState(current.status(), Set.of("PENDING_REVIEW"), "demand is not pending review");
        return transitionDemand(
                current, expectedVersion, decision.approved() ? "OPEN" : "REJECTED",
                decision.comment(), decision.approved() ? "APPROVE" : "REJECT", actor);
    }

    @Transactional
    public DemandView closeDemand(
            UUID id, long expectedVersion, CloseDemandRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView current = demand(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireState(current.status(), Set.of("OPEN"), "only an open demand can be closed");
        return transitionDemand(current, expectedVersion, "CLOSED", request.reason(), "CLOSE", actor);
    }

    @Transactional
    public DemandView disableDemand(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView current = demand(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        if ("DISABLED".equals(current.status())) {
            throw new PreconditionFailedException("demand is already disabled");
        }
        return transitionDemand(current, expectedVersion, "DISABLED", null, "DISABLE", actor);
    }

    @Transactional
    public DemandView deleteDemand(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView current = demand(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        DemandView deleted = store.softDeleteDemand(id, expectedVersion, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "SOFT_DELETE", "COOPERATION_DEMAND", deleted.id(), deleted.enterpriseId(),
                deleted.version(), deleted);
        return deleted;
    }

    @Transactional
    public DemandView restoreDemand(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView current = demand(id, actor, true);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        DemandView restored = store.restoreDemand(id, expectedVersion, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "RESTORE", "COOPERATION_DEMAND", restored.id(), restored.enterpriseId(),
                restored.version(), restored);
        return restored;
    }

    private OfferingView transitionOffering(
            OfferingView current, long expectedVersion, String state, String action, ActorScope actor) {
        requireOperational(current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        OfferingView updated = store.transitionOffering(current.id(), expectedVersion, state, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, action, "PRODUCT_SERVICE", updated.id(), updated.enterpriseId(), updated.version(), updated);
        return updated;
    }

    private DemandView transitionDemand(
            DemandView current,
            long expectedVersion,
            String state,
            String reason,
            String action,
            ActorScope actor) {
        requireOperational(current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        DemandView updated = store.transitionDemand(current.id(), expectedVersion, state, reason, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, action, "COOPERATION_DEMAND", updated.id(), updated.enterpriseId(),
                updated.version(), updated);
        return updated;
    }

    private void record(
            ActorScope actor, String action, String type, UUID id, UUID enterpriseId, long version, Object snapshot) {
        store.recordChange(actor, action, type, id, actor.associationId(), enterpriseId, version, snapshot);
    }

    private static UUID requireEnterprise(ActorScope actor) {
        if (actor.enterpriseId() == null
                || (!actor.isEnterpriseAdmin() && !actor.isSystemAdmin())) {
            throw new ForbiddenException(
                    "ENTERPRISE_CONTEXT_REQUIRED", "an enterprise administrator identity is required");
        }
        return actor.enterpriseId();
    }

    private void requireOwner(ActorScope actor, UUID enterpriseId) {
        requireOperational(enterpriseId);
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemEnterpriseWrite(actor, enterpriseId, store);
            return;
        }
        if (!actor.isEnterpriseAdmin() || !enterpriseId.equals(actor.enterpriseId())) {
            throw new ForbiddenException("ENTERPRISE_SCOPE_VIOLATION", "enterprise can only edit its own data");
        }
    }

    private void requireOwnerOrAssociation(ActorScope actor, UUID enterpriseId) {
        requireOperational(enterpriseId);
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemEnterpriseWrite(actor, enterpriseId, store);
            return;
        }
        if (actor.isAssociationStaff()) {
            if (store.enterpriseBelongsToAssociation(enterpriseId, actor.associationId())) {
                return;
            }
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION", "association can only manage resources owned by its members");
        }
        requireOwner(actor, enterpriseId);
    }

    private static void requireReviewer(ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            throw new ForbiddenException("REVIEWER_REQUIRED", "association reviewer identity is required");
        }
    }

    private void requireReviewAssociation(ActorScope actor, UUID enterpriseId) {
        if (actor.associationId() == null
                || !store.enterpriseBelongsToAssociation(enterpriseId, actor.associationId())) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "association can only review resources owned by its members");
        }
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemEnterpriseWrite(actor, enterpriseId, store);
        }
    }

    private static boolean canReadDeleted(ActorScope actor) {
        return actor.isSystemAdmin() || actor.isAssociationStaff() || actor.isEnterpriseAdmin();
    }

    private void requireOperational(UUID enterpriseId) {
        if (enterpriseId == null || !enterpriseLifecycle.isOperational(enterpriseId)) {
            throw new PreconditionFailedException(
                    "enterprise must be active before participating in ecosystem workflows");
        }
    }

    Optional<OfferingView> authorizedOffering(OfferingView value, ActorScope actor) {
        if (!isCrossAssociationRead(value.enterpriseId(), actor)) {
            return Optional.of(value);
        }
        return partnerFields.authorizedFields(actor, value.enterpriseId(), value.kind(), value.id())
                .map(fields -> new OfferingView(
                        value.id(), value.enterpriseId(), visible(fields, "enterpriseName") ? value.enterpriseName() : null,
                        visible(fields, "name") ? value.name() : null, value.kind(),
                        visible(fields, "description") ? value.description() : null,
                        visible(fields, "scenarios") ? value.scenarios() : List.of(),
                        visible(fields, "qualifications") ? value.qualifications() : List.of(),
                        value.visibility(), value.status(), value.version(), value.disabled(), value.updatedAt()));
    }

    Optional<DemandView> authorizedDemand(DemandView value, ActorScope actor) {
        if (!isCrossAssociationRead(value.enterpriseId(), actor)) {
            return Optional.of(value);
        }
        return partnerFields.authorizedFields(actor, value.enterpriseId(), "DEMAND", value.id())
                .map(fields -> new DemandView(
                        value.id(), value.enterpriseId(), visible(fields, "enterpriseName") ? value.enterpriseName() : null,
                        visible(fields, "title") ? value.title() : null,
                        visible(fields, "description") ? value.description() : null,
                        visible(fields, "scenarios") ? value.scenarios() : List.of(),
                        visible(fields, "requiredCapabilities") ? value.requiredCapabilities() : List.of(),
                        value.visibility(), visible(fields, "budgetMin") ? value.budgetMin() : null,
                        visible(fields, "budgetMax") ? value.budgetMax() : null,
                        visible(fields, "responseDeadline") ? value.responseDeadline() : null,
                        value.status(), null, value.version(), value.disabled(), value.updatedAt()));
    }

    private boolean isCrossAssociationRead(UUID enterpriseId, ActorScope actor) {
        return !actor.isSystemAdmin()
                && (actor.associationId() == null
                || !store.enterpriseBelongsToAssociation(enterpriseId, actor.associationId()));
    }

    private static boolean visible(Set<String> fields, String field) {
        return fields.contains(field);
    }

    private static void requireState(String actual, Set<String> expected, String message) {
        if (!expected.contains(actual)) {
            throw new PreconditionFailedException(message);
        }
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw stale();
        }
    }

    private static PreconditionFailedException stale() {
        return new PreconditionFailedException("resource version is stale; reload and retry with the latest ETag");
    }

    private static void validateBudget(BigDecimal minimum, BigDecimal maximum) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new PreconditionFailedException("budgetMin must not exceed budgetMax");
        }
    }
}
