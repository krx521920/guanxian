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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class EcosystemCatalogService {
    private static final Set<String> EDITABLE_OFFERING_STATES = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE_DEMAND_STATES = Set.of("DRAFT", "REJECTED");
    private static final Set<String> DEMAND_VISIBILITIES = Set.of(
            "PRIVATE", "MEMBERS", "PARTNERS", "PUBLIC");
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
        return withAllowedActions(created, offeringActions(created, actor));
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
        return withAllowedActions(updated, offeringActions(updated, actor));
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
    public OfferingView enableOffering(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        OfferingView current = offering(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireState(current.status(), Set.of("DISABLED"), "only a disabled offering can be enabled");
        return transitionOffering(current, expectedVersion, "DRAFT", "ENABLE", actor);
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
        return withAllowedActions(deleted, offeringActions(deleted, actor));
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
        return withAllowedActions(restored, offeringActions(restored, actor));
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
        validateDemandRequest(request);
        UUID enterpriseId = requireEnterprise(actor);
        requireOperational(enterpriseId);
        DemandView created = store.createDemand(enterpriseId, request, actor);
        record(actor, "CREATE", "COOPERATION_DEMAND", created.id(), created.enterpriseId(), created.version(), created);
        return withAllowedActions(created, demandActions(created, actor));
    }

    @Transactional
    public DemandView updateDemand(
            UUID id, long expectedVersion, DemandUpsertRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        validateDemandRequest(request);
        DemandView current = demand(id, actor, false);
        requireOwner(actor, current.enterpriseId());
        requireState(current.status(), EDITABLE_DEMAND_STATES, "demand must be DRAFT or REJECTED to edit");
        requireVersion(current.version(), expectedVersion);
        DemandView updated = store.updateDemand(id, expectedVersion, request, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, "UPDATE", "COOPERATION_DEMAND", updated.id(), updated.enterpriseId(),
                updated.version(), updated);
        return withAllowedActions(updated, demandActions(updated, actor));
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
        if (decision.approved()) {
            requireResponseWindow(current);
        }
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
        requireState(current.status(), Set.of("DRAFT", "PENDING_REVIEW", "REJECTED", "OPEN"),
                "a closed or already disabled demand cannot be disabled");
        return transitionDemand(current, expectedVersion, "DISABLED", null, "DISABLE", actor);
    }

    @Transactional
    public DemandView enableDemand(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView current = demand(id, actor, false);
        requireOwnerOrAssociation(actor, current.enterpriseId());
        requireState(current.status(), Set.of("DISABLED"), "only a disabled demand can be enabled");
        return transitionDemand(current, expectedVersion, "DRAFT", null, "ENABLE", actor);
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
        return withAllowedActions(deleted, demandActions(deleted, actor));
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
        return withAllowedActions(restored, demandActions(restored, actor));
    }

    private OfferingView transitionOffering(
            OfferingView current, long expectedVersion, String state, String action, ActorScope actor) {
        requireOperational(current.enterpriseId());
        requireVersion(current.version(), expectedVersion);
        OfferingView updated = store.transitionOffering(current.id(), expectedVersion, state, actor)
                .orElseThrow(EcosystemCatalogService::stale);
        record(actor, action, "PRODUCT_SERVICE", updated.id(), updated.enterpriseId(), updated.version(), updated);
        return withAllowedActions(updated, offeringActions(updated, actor));
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
        return withAllowedActions(updated, demandActions(updated, actor));
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
            return Optional.of(withAllowedActions(value, offeringActions(value, actor)));
        }
        return partnerFields.authorizedFields(actor, value.enterpriseId(), value.kind(), value.id())
                .map(fields -> new OfferingView(
                        value.id(), value.enterpriseId(), visible(fields, "enterpriseName") ? value.enterpriseName() : null,
                        visible(fields, "name") ? value.name() : null, value.kind(),
                        visible(fields, "description") ? value.description() : null,
                        visible(fields, "scenarios") ? value.scenarios() : List.of(),
                        visible(fields, "qualifications") ? value.qualifications() : List.of(),
                        value.visibility(), value.status(), value.version(), value.disabled(),
                        value.deleted(), value.deletedAt(), value.updatedAt(), Set.of()));
    }

    Optional<DemandView> authorizedDemand(DemandView value, ActorScope actor) {
        if (!isCrossAssociationRead(value.enterpriseId(), actor)) {
            return Optional.of(withAllowedActions(value, demandActions(value, actor)));
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
                        value.status(), null, value.version(), value.disabled(),
                        value.deleted(), value.deletedAt(), value.updatedAt(), Set.of()));
    }

    private Set<String> offeringActions(OfferingView value, ActorScope actor) {
        boolean owner = canOwn(actor, value.enterpriseId());
        boolean manager = canManage(actor, value.enterpriseId());
        if (value.deleted()) {
            return manager ? Set.of("RESTORE") : Set.of();
        }
        HashSet<String> actions = new HashSet<>();
        if (owner && EDITABLE_OFFERING_STATES.contains(value.status())) {
            actions.add("UPDATE");
            actions.add("SUBMIT");
        }
        if (canReview(actor, value.enterpriseId()) && "PENDING_REVIEW".equals(value.status())) {
            actions.add("REVIEW");
        }
        if (manager) {
            actions.add("DELETE");
            actions.add("DISABLED".equals(value.status()) ? "ENABLE" : "DISABLE");
        }
        return Set.copyOf(actions);
    }

    private Set<String> demandActions(DemandView value, ActorScope actor) {
        boolean owner = canOwn(actor, value.enterpriseId());
        boolean manager = canManage(actor, value.enterpriseId());
        if (value.deleted()) {
            return manager ? Set.of("RESTORE") : Set.of();
        }
        HashSet<String> actions = new HashSet<>();
        if (owner && EDITABLE_DEMAND_STATES.contains(value.status())) {
            actions.add("UPDATE");
            actions.add("SUBMIT");
        }
        if (canReview(actor, value.enterpriseId()) && "PENDING_REVIEW".equals(value.status())) {
            actions.add("REVIEW");
        }
        if (manager) {
            actions.add("DELETE");
            if ("DISABLED".equals(value.status())) {
                actions.add("ENABLE");
            } else if (!"CLOSED".equals(value.status())) {
                actions.add("DISABLE");
            }
            if ("OPEN".equals(value.status())) {
                actions.add("CLOSE");
            }
        }
        return Set.copyOf(actions);
    }

    private boolean canOwn(ActorScope actor, UUID enterpriseId) {
        if (!enterpriseLifecycle.isOperational(enterpriseId)) {
            return false;
        }
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null
                    && EcosystemScopeGuard.systemCanReadEnterprise(actor, enterpriseId, store);
        }
        return actor.isEnterpriseAdmin() && enterpriseId.equals(actor.enterpriseId());
    }

    private boolean canManage(ActorScope actor, UUID enterpriseId) {
        if (!enterpriseLifecycle.isOperational(enterpriseId)) {
            return false;
        }
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null
                    && EcosystemScopeGuard.systemCanReadEnterprise(actor, enterpriseId, store);
        }
        if (actor.isAssociationStaff()) {
            return actor.associationId() != null
                    && store.enterpriseBelongsToAssociation(enterpriseId, actor.associationId());
        }
        return actor.isEnterpriseAdmin() && enterpriseId.equals(actor.enterpriseId());
    }

    private boolean canReview(ActorScope actor, UUID enterpriseId) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            return false;
        }
        return actor.associationId() != null
                && store.enterpriseBelongsToAssociation(enterpriseId, actor.associationId())
                && (!actor.isSystemAdmin()
                || EcosystemScopeGuard.systemCanReadEnterprise(actor, enterpriseId, store));
    }

    private static OfferingView withAllowedActions(OfferingView value, Set<String> actions) {
        return new OfferingView(
                value.id(), value.enterpriseId(), value.enterpriseName(), value.name(), value.kind(),
                value.description(), value.scenarios(), value.qualifications(), value.visibility(),
                value.status(), value.version(), value.disabled(), value.deleted(), value.deletedAt(),
                value.updatedAt(), actions);
    }

    private static DemandView withAllowedActions(DemandView value, Set<String> actions) {
        return new DemandView(
                value.id(), value.enterpriseId(), value.enterpriseName(), value.title(), value.description(),
                value.scenarios(), value.requiredCapabilities(), value.visibility(), value.budgetMin(),
                value.budgetMax(), value.responseDeadline(), value.status(), value.closeReason(),
                value.version(), value.disabled(), value.deleted(), value.deletedAt(), value.updatedAt(), actions);
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

    private static void validateDemandRequest(DemandUpsertRequest request) {
        validateBudget(request.budgetMin(), request.budgetMax());
        String visibility = request.visibility() == null || request.visibility().isBlank()
                ? "MEMBERS" : request.visibility().trim().toUpperCase(Locale.ROOT);
        if (!DEMAND_VISIBILITIES.contains(visibility)) {
            throw new PreconditionFailedException(
                    "visibility must be PRIVATE, MEMBERS, PARTNERS or PUBLIC");
        }
        if (request.responseDeadline() != null
                && !request.responseDeadline().isAfter(Instant.now())) {
            throw new PreconditionFailedException("responseDeadline must be in the future");
        }
    }

    private static void requireResponseWindow(DemandView demand) {
        if (!DEMAND_VISIBILITIES.contains(demand.visibility())
                || demand.responseDeadline() != null
                && !demand.responseDeadline().isAfter(Instant.now())) {
            throw new PreconditionFailedException(
                    "demand response window has expired or its visibility is unsupported");
        }
    }
}
