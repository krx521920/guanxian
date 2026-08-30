package com.guanxian.platform.ecosystem;

import com.guanxian.platform.ai.AiTextService;
import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.EnterpriseLifecycle;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.PartnerFieldAuthorization;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EcosystemMatchService {
    private static final Comparator<PersistedMatchView> OUTBOUND_MATCH_ORDER = Comparator
            .comparing(PersistedMatchView::score, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PersistedMatchView::id);
    private final MemberDirectory memberDirectory;
    private final AiTextService aiTextService;
    private final EcosystemCatalogService catalogService;
    private final EcosystemMatchStore matchStore;
    private final EcosystemCatalogStore catalogStore;
    private final EcosystemWorkflowStore workflowStore;
    private final EnterpriseLifecycle enterpriseLifecycle;
    private final PartnerFieldAuthorization partnerFields;

    @Autowired
    public EcosystemMatchService(
            MemberDirectory memberDirectory,
            AiTextService aiTextService,
            EcosystemCatalogService catalogService,
            EcosystemMatchStore matchStore,
            EcosystemCatalogStore catalogStore,
            EcosystemWorkflowStore workflowStore,
            EnterpriseLifecycle enterpriseLifecycle,
            PartnerFieldAuthorization partnerFields) {
        this.memberDirectory = memberDirectory;
        this.aiTextService = aiTextService;
        this.catalogService = catalogService;
        this.matchStore = matchStore;
        this.catalogStore = catalogStore;
        this.workflowStore = workflowStore;
        this.enterpriseLifecycle = enterpriseLifecycle;
        this.partnerFields = partnerFields;
    }

    public EcosystemMatchService(
            MemberDirectory memberDirectory,
            AiTextService aiTextService,
            EcosystemCatalogService catalogService,
            EcosystemMatchStore matchStore,
            EcosystemCatalogStore catalogStore,
            EnterpriseLifecycle enterpriseLifecycle) {
        this(memberDirectory, aiTextService, catalogService, matchStore, catalogStore,
                null, enterpriseLifecycle, PartnerFieldAuthorization.allowAll());
    }

    public EcosystemMatchService(
            MemberDirectory memberDirectory,
            AiTextService aiTextService,
            EcosystemCatalogService catalogService,
            EcosystemMatchStore matchStore,
            EcosystemCatalogStore catalogStore,
            EnterpriseLifecycle enterpriseLifecycle,
            PartnerFieldAuthorization partnerFields) {
        this(memberDirectory, aiTextService, catalogService, matchStore, catalogStore,
                null, enterpriseLifecycle, partnerFields);
    }

    EcosystemMatchService(
            MemberDirectory memberDirectory,
            AiTextService aiTextService,
            EcosystemCatalogService catalogService,
            EcosystemMatchStore matchStore,
            EcosystemCatalogStore catalogStore) {
        this(memberDirectory, aiTextService, catalogService, matchStore, catalogStore,
                null, enterpriseId -> true, PartnerFieldAuthorization.allowAll());
    }

    @Transactional
    public List<PersistedMatchView> persisted(ActorScope actor) {
        if (workflowStore != null) {
            return InMemoryEcosystemUnitOfWork.execute(
                    matchStore, workflowStore, () -> persistedInternal(actor));
        }
        return persistedInternal(actor);
    }

    private List<PersistedMatchView> persistedInternal(ActorScope actor) {
        return outboundMatches(normalizeExpiredInvitations(matchStore.list(actor), actor), actor);
    }

    public List<EcosystemMatch> match(MatchRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        int limit = request.limit() == null ? 5 : request.limit();
        String context = String.join(" ", request.demandTitle(), request.scene(), nullToEmpty(request.requirements()));
        List<String> tags = aiTextService.extractTags(context);
        return memberDirectory.findAll(null, actor).stream()
                .filter(member -> enterpriseLifecycle.isOperational(member.id()))
                .filter(member -> !member.name().equalsIgnoreCase(request.demandCompany()))
                .map(member -> score(member, request, tags))
                .sorted(Comparator.comparing(
                                EcosystemMatch::score,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(EcosystemMatch::supplierCompany)
                        .thenComparing(EcosystemMatch::id))
                .limit(limit)
                .toList();
    }

    @Transactional
    public List<PersistedMatchView> generate(UUID demandId, Integer requestedLimit, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        DemandView demand = catalogService.demand(demandId, actor, false);
        if (!"OPEN".equals(demand.status())) {
            throw new PreconditionFailedException("matches can only be generated for an OPEN demand");
        }
        requireDemandOwnerOrAssociation(demand, actor);
        int limit = requestedLimit == null ? 5 : Math.min(Math.max(requestedLimit, 1), 20);
        String scene = String.join(" ", demand.scenarios());
        String requirements = String.join(" ", demand.requiredCapabilities());
        String context = String.join(" ", demand.title(), scene, requirements, demand.description());
        List<String> tags = aiTextService.extractTags(context);
        MatchRequest request = new MatchRequest(
                demand.enterpriseName() == null ? demand.enterpriseId().toString() : demand.enterpriseName(),
                demand.title(),
                scene.isBlank() ? "未分类场景" : scene,
                requirements,
                limit);
        Map<UUID, MemberProfile> memberProfiles = memberDirectory.findAll(null, actor).stream()
                .filter(member -> enterpriseLifecycle.isOperational(member.id()))
                .collect(Collectors.toMap(MemberProfile::id, Function.identity(), (left, right) -> left));
        Map<UUID, MatchCandidateDraft> bestByEnterprise = new LinkedHashMap<>();
        visibleActiveOfferings(actor).stream()
                .filter(offering -> !offering.enterpriseId().equals(demand.enterpriseId()))
                .map(offering -> score(offering, memberProfiles.get(offering.enterpriseId()), request, tags))
                .forEach(candidate -> bestByEnterprise.merge(
                        candidate.candidateEnterpriseId(), candidate, EcosystemMatchService::betterCandidate));
        List<MatchCandidateDraft> candidates = bestByEnterprise.values().stream()
                .sorted(Comparator.comparingInt(MatchCandidateDraft::score).reversed()
                        .thenComparing(MatchCandidateDraft::supplierCompany)
                        .thenComparing(MatchCandidateDraft::candidateEnterpriseId))
                .limit(limit)
                .toList();
        List<PersistedMatchView> persisted = matchStore.upsert(demand, candidates, actor);
        for (PersistedMatchView value : persisted) {
            catalogStore.recordChange(
                    actor, "GENERATE_OR_REFRESH", "ECOSYSTEM_MATCH", value.id(),
                    actor.associationId(), value.demandEnterpriseId(), value.version(), value);
        }
        return outboundMatches(persisted, actor);
    }

    @Transactional(readOnly = true)
    public EcosystemPage<DemandView> generationDemands(
            ActorScope actor, int requestedPage, int requestedSize) {
        EcosystemScopeGuard.requireWriteContext(actor);
        int page = Math.max(requestedPage, 0);
        int size = Math.min(Math.max(requestedSize, 1), 100);
        List<DemandView> eligible = new ArrayList<>();
        int sourcePage = 0;
        EcosystemPage<DemandView> source;
        do {
            source = catalogService.demands(actor, null, false, sourcePage, 100);
            source.items().stream()
                    .filter(demand -> "OPEN".equals(demand.status()))
                    .filter(demand -> canGenerateDemand(demand, actor))
                    .forEach(eligible::add);
            sourcePage++;
        } while ((long) sourcePage * source.size() < source.total());
        eligible.sort(Comparator.comparing(DemandView::updatedAt).reversed()
                .thenComparing(DemandView::id));
        long offset = (long) page * size;
        if (offset >= eligible.size()) {
            return new EcosystemPage<>(List.of(), eligible.size(), page, size);
        }
        int from = Math.toIntExact(offset);
        int to = Math.min(from + size, eligible.size());
        return new EcosystemPage<>(eligible.subList(from, to), eligible.size(), page, size);
    }

    @Transactional
    public List<PersistedMatchView> persisted(UUID demandId, ActorScope actor) {
        if (workflowStore != null) {
            return InMemoryEcosystemUnitOfWork.execute(
                    matchStore, workflowStore, () -> persistedInternal(demandId, actor));
        }
        return persistedInternal(demandId, actor);
    }

    private List<PersistedMatchView> persistedInternal(UUID demandId, ActorScope actor) {
        return outboundMatches(
                normalizeExpiredInvitations(matchStore.list(demandId, actor), actor), actor);
    }

    @Transactional
    public PersistedMatchView recommend(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            throw new ForbiddenException("MATCH_REVIEWER_REQUIRED", "association reviewer identity is required");
        }
        PersistedMatchView current = findRawAuthorized(id, actor);
        requireOperationalMatch(current);
        requireOwningAssociation(current, actor);
        MatchLifecycle.requireRecommendationAllowed(current);
        if (current.version() != expectedVersion) {
            throw stale();
        }
        PersistedMatchView updated = matchStore.recommend(current.id(), expectedVersion, actor)
                .orElseThrow(EcosystemMatchService::stale);
        catalogStore.recordChange(
                actor, "RECOMMEND", "ECOSYSTEM_MATCH", updated.id(),
                actor.associationId(), updated.demandEnterpriseId(), updated.version(), updated);
        return outboundMatch(updated, actor);
    }

    @Transactional
    public PersistedMatchView confirm(UUID id, long expectedVersion, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView current = findRawAuthorized(id, actor);
        requireOperationalMatch(current);
        if (actor.enterpriseId() == null
                || (!actor.enterpriseId().equals(current.demandEnterpriseId())
                && !actor.enterpriseId().equals(current.candidateEnterpriseId()))) {
            throw new ForbiddenException(
                    "MATCH_PARTICIPANT_REQUIRED", "only an enterprise participating in the match can confirm it");
        }
        requireEnterpriseWriter(actor);
        MatchLifecycle.requireConfirmable(current);
        if ((current.demandEnterpriseId().equals(actor.enterpriseId())
                && current.demandConfirmedAt() != null)
                || (current.candidateEnterpriseId().equals(actor.enterpriseId())
                && current.candidateConfirmedAt() != null)) {
            throw new PreconditionFailedException("this enterprise has already confirmed the match");
        }
        if (current.version() != expectedVersion) {
            throw stale();
        }
        PersistedMatchView updated = matchStore.confirm(
                        current.id(), expectedVersion, actor.enterpriseId(), actor)
                .orElseThrow(EcosystemMatchService::stale);
        String action = updated.state().equals(MatchLifecycle.CONFIRMED)
                ? "COMPLETE_BILATERAL_CONFIRMATION" : "CONFIRM_PARTICIPATION";
        catalogStore.recordChange(
                actor, action, "ECOSYSTEM_MATCH", updated.id(),
                actor.associationId(), updated.demandEnterpriseId(), updated.version(), updated);
        return outboundMatch(updated, actor);
    }

    @Transactional
    public PersistedMatchView close(
            UUID id, long expectedVersion, MatchCloseRequest request, ActorScope actor) {
        if (workflowStore != null) {
            return InMemoryEcosystemUnitOfWork.execute(
                    matchStore, workflowStore,
                    () -> closeInternal(id, expectedVersion, request, actor));
        }
        return closeInternal(id, expectedVersion, request, actor);
    }

    private PersistedMatchView closeInternal(
            UUID id, long expectedVersion, MatchCloseRequest request, ActorScope actor) {
        EcosystemScopeGuard.requireWriteContext(actor);
        PersistedMatchView current = findRawAuthorized(id, actor);
        requireOperationalMatch(current);
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(
                current.demandEnterpriseId(), actor.associationId());
        boolean allowed;
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemMatchWrite(actor, current, catalogStore);
            allowed = systemOwnsDemand(current, actor);
        } else {
            allowed = owningAssociation || current.demandEnterpriseId().equals(actor.enterpriseId())
                    && isEnterpriseWriter(actor);
        }
        if (!allowed) {
            throw new ForbiddenException(
                    "MATCH_CLOSE_FORBIDDEN", "only the demand owner or association can close the match");
        }
        MatchLifecycle.requireClosable(current);
        if (current.version() != expectedVersion) {
            throw stale();
        }
        String reason = request.reason().trim();
        cancelPendingInvitations(current, reason, actor);
        return transition(
                current, expectedVersion, MatchLifecycle.CLOSED, reason, "CLOSE", actor);
    }

    private PersistedMatchView findRawAuthorized(UUID id, ActorScope actor) {
        PersistedMatchView value = matchStore.find(id, actor)
                .filter(match -> canReadMatch(match, actor))
                .orElseThrow(() -> new NotFoundException("ecosystem match", id));
        if (authorizedMatch(value, actor).isEmpty()) {
            throw new NotFoundException("ecosystem match", id);
        }
        return value;
    }

    Optional<PersistedMatchView> authorizedMatch(PersistedMatchView value, ActorScope actor) {
        if (MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                && !canReadUnrecommended(value, actor)) {
            return Optional.empty();
        }
        if (actor.isSystemAdmin()) {
            return EcosystemScopeGuard.systemCanReadMatch(actor, value, catalogStore)
                    ? Optional.of(withAllowedActions(value, actor)) : Optional.empty();
        }
        if (actorParticipatesInMatch(value, actor)) {
            return Optional.of(withAllowedActions(value, actor));
        }
        Optional<Set<String>> demandAuthorization = participantFields(
                value.demandEnterpriseId(), value.id(), actor);
        if (demandAuthorization.isEmpty()) {
            return Optional.empty();
        }
        List<Set<String>> ownerAuthorizations = new ArrayList<>();
        ownerAuthorizations.add(demandAuthorization.orElseThrow());
        if (!value.candidateEnterpriseId().equals(value.demandEnterpriseId())) {
            Optional<Set<String>> candidateAuthorization = participantFields(
                    value.candidateEnterpriseId(), value.id(), actor);
            if (candidateAuthorization.isEmpty()) {
                return Optional.empty();
            }
            ownerAuthorizations.add(candidateAuthorization.orElseThrow());
        }
        Set<String> fields = new java.util.LinkedHashSet<>(ownerAuthorizations.getFirst());
        ownerAuthorizations.stream().skip(1).forEach(fields::retainAll);
        if (fields.isEmpty()) return Optional.empty();
        return Optional.of(new PersistedMatchView(
                value.id(), value.demandId(), value.demandEnterpriseId(), value.candidateEnterpriseId(),
                fields.contains("demandCompany") ? value.demandCompany() : null,
                fields.contains("demandTitle") ? value.demandTitle() : null,
                fields.contains("scene") ? value.scene() : null,
                fields.contains("supplierCompany") ? value.supplierCompany() : null,
                fields.contains("solution") ? value.solution() : null,
                fields.contains("score") ? value.score() : null,
                fields.contains("reasons") ? value.reasons() : List.of(),
                fields.contains("state") ? value.state() : null,
                null, null, null, null, value.version(), null, Set.of()));
    }

    private Optional<Set<String>> participantFields(
            UUID enterpriseId, UUID matchId, ActorScope actor) {
        return partnerFields.authorizedFields(actor, enterpriseId, "MATCH", matchId)
                .map(fields -> fields.stream()
                        .filter(field -> field != null
                                && PartnerFieldAuthorization.MATCH_FIELDS.contains(field))
                        .collect(Collectors.toUnmodifiableSet()))
                .filter(fields -> !fields.isEmpty());
    }

    private boolean actorParticipatesInMatch(PersistedMatchView value, ActorScope actor) {
        if (actor.enterpriseId() != null
                && (actor.enterpriseId().equals(value.demandEnterpriseId())
                || actor.enterpriseId().equals(value.candidateEnterpriseId()))) {
            return true;
        }
        return actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId());
    }

    private List<PersistedMatchView> outboundMatches(
            List<PersistedMatchView> values, ActorScope actor) {
        return values.stream()
                .filter(value -> canReadMatch(value, actor))
                .map(value -> authorizedMatch(value, actor))
                .flatMap(Optional::stream)
                .sorted(OUTBOUND_MATCH_ORDER)
                .toList();
    }

    private PersistedMatchView outboundMatch(PersistedMatchView value, ActorScope actor) {
        return authorizedMatch(value, actor)
                .orElseThrow(() -> new NotFoundException("ecosystem match", value.id()));
    }

    private PersistedMatchView transition(
            PersistedMatchView current,
            long expectedVersion,
            String target,
            String reason,
            String action,
            ActorScope actor) {
        if (current.version() != expectedVersion) {
            throw stale();
        }
        PersistedMatchView updated = matchStore.transition(
                        current.id(), expectedVersion, target, reason, actor)
                .orElseThrow(EcosystemMatchService::stale);
        catalogStore.recordChange(
                actor, action, "ECOSYSTEM_MATCH", updated.id(),
                actor.associationId(), updated.demandEnterpriseId(), updated.version(), updated);
        return outboundMatch(updated, actor);
    }

    private void requireDemandOwnerOrAssociation(DemandView demand, ActorScope actor) {
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(demand.enterpriseId(), actor.associationId());
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemEnterpriseWrite(actor, demand.enterpriseId(), catalogStore);
            if (actor.enterpriseId() == null || demand.enterpriseId().equals(actor.enterpriseId())) {
                return;
            }
        } else if (owningAssociation || demand.enterpriseId().equals(actor.enterpriseId())
                && isEnterpriseWriter(actor)) {
            return;
        }
        throw new ForbiddenException(
                "DEMAND_SCOPE_VIOLATION", "only the demand owner or association can generate matches");
    }

    private boolean canGenerateDemand(DemandView demand, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null
                    && EcosystemScopeGuard.systemCanReadEnterprise(
                    actor, demand.enterpriseId(), catalogStore)
                    && (actor.enterpriseId() == null
                    || actor.enterpriseId().equals(demand.enterpriseId()));
        }
        return demand.enterpriseId().equals(actor.enterpriseId())
                && actor.isEnterpriseAdmin()
                || actor.isAssociationStaff() && actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                demand.enterpriseId(), actor.associationId());
    }

    private static boolean isEnterpriseWriter(ActorScope actor) {
        return actor.isEnterpriseAdmin() || actor.isAssociationStaff() || actor.isSystemAdmin();
    }

    private static void requireEnterpriseWriter(ActorScope actor) {
        if (!isEnterpriseWriter(actor)) {
            throw new ForbiddenException(
                    "ENTERPRISE_WRITE_REQUIRED", "an enterprise write identity is required");
        }
    }

    private void requireOwningAssociation(PersistedMatchView match, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            EcosystemScopeGuard.requireSystemMatchWrite(actor, match, catalogStore);
            if (systemOwnsDemand(match, actor)) {
                return;
            }
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION",
                    "selected system context does not own the demand enterprise");
        }
        if (!actor.isAssociationStaff()
                || !catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId())) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION", "association can only manage matches owned by its members");
        }
    }

    private boolean canReadMatch(PersistedMatchView value, ActorScope actor) {
        if (MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                && !canReadUnrecommended(value, actor)) {
            return false;
        }
        if (actor.isSystemAdmin()) {
            return EcosystemScopeGuard.systemCanReadMatch(actor, value, catalogStore);
        }
        if (actor.isAssociationStaff()) {
            return actor.associationId() != null;
        }
        return enterpriseLifecycle.isOperational(value.demandEnterpriseId())
                && enterpriseLifecycle.isOperational(value.candidateEnterpriseId())
                && !value.demandEnterpriseId().equals(value.candidateEnterpriseId())
                && (value.demandEnterpriseId().equals(actor.enterpriseId())
                || value.candidateEnterpriseId().equals(actor.enterpriseId()));
    }

    private boolean canReadUnrecommended(PersistedMatchView value, ActorScope actor) {
        if (actor.isSystemAdmin()
                && actor.associationId() == null
                && actor.enterpriseId() == null) {
            return true;
        }
        if (value.demandEnterpriseId().equals(actor.enterpriseId())) {
            return true;
        }
        return actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId())
                && (actor.isAssociationStaff() || actor.isSystemAdmin())
                && (!actor.isSystemAdmin() || actor.enterpriseId() == null
                || value.demandEnterpriseId().equals(actor.enterpriseId()));
    }

    private boolean systemOwnsDemand(PersistedMatchView match, ActorScope actor) {
        return actor.isSystemAdmin()
                && actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId())
                && (actor.enterpriseId() == null
                || actor.enterpriseId().equals(match.demandEnterpriseId()));
    }

    private void requireOperationalMatch(PersistedMatchView value) {
        if (value.demandEnterpriseId().equals(value.candidateEnterpriseId())) {
            throw new PreconditionFailedException("a demand enterprise cannot be matched with itself");
        }
        if (!enterpriseLifecycle.isOperational(value.demandEnterpriseId())
                || !enterpriseLifecycle.isOperational(value.candidateEnterpriseId())) {
            throw new PreconditionFailedException(
                    "both enterprises must be active before participating in ecosystem workflows");
        }
    }

    private List<PersistedMatchView> normalizeExpiredInvitations(
            List<PersistedMatchView> values, ActorScope actor) {
        return values.stream()
                .map(value -> normalizeExpiredInvitation(value, actor))
                .toList();
    }

    private PersistedMatchView normalizeExpiredInvitation(
            PersistedMatchView value, ActorScope actor) {
        if (workflowStore == null
                || !MatchLifecycle.INVITED.equals(value.state())
                || !canMaintainInvitation(value, actor)) {
            return value;
        }
        List<MatchInvitationView> expired = workflowStore.expirePendingInvitations(value.id(), actor);
        for (MatchInvitationView invitation : expired) {
            catalogStore.recordChange(
                    actor, "EXPIRE_INVITATION", "MATCH_INVITATION", invitation.id(),
                    actor.associationId(), value.demandEnterpriseId(),
                    invitation.version(), invitation);
        }
        if (expired.isEmpty() || workflowStore.hasPendingInvitation(value.id(), actor)) {
            return value;
        }
        PersistedMatchView updated = matchStore.transition(
                        value.id(), value.version(), MatchLifecycle.CONFIRMED, null, actor)
                .orElseThrow(EcosystemMatchService::stale);
        catalogStore.recordChange(
                actor, "EXPIRE_INVITATION", "ECOSYSTEM_MATCH", updated.id(),
                actor.associationId(), updated.demandEnterpriseId(), updated.version(), updated);
        return updated;
    }

    private void cancelPendingInvitations(
            PersistedMatchView match, String reason, ActorScope actor) {
        if (workflowStore == null) {
            return;
        }
        for (MatchInvitationView invitation :
                workflowStore.cancelPendingInvitations(match.id(), reason, actor)) {
            catalogStore.recordChange(
                    actor, "CANCEL_INVITATION", "MATCH_INVITATION", invitation.id(),
                    actor.associationId(), match.demandEnterpriseId(),
                    invitation.version(), invitation);
        }
    }

    private boolean canMaintainInvitation(PersistedMatchView match, ActorScope actor) {
        if (actor.isSystemAdmin()) {
            return actor.associationId() != null
                    && EcosystemScopeGuard.systemCanReadMatch(actor, match, catalogStore);
        }
        return actor.enterpriseId() != null
                && (actor.enterpriseId().equals(match.demandEnterpriseId())
                || actor.enterpriseId().equals(match.candidateEnterpriseId()))
                || actor.isAssociationStaff() && actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                match.demandEnterpriseId(), actor.associationId());
    }

    private PersistedMatchView withAllowedActions(
            PersistedMatchView value, ActorScope actor) {
        Set<String> actions = allowedActions(value, actor);
        return new PersistedMatchView(
                value.id(), value.demandId(), value.demandEnterpriseId(),
                value.candidateEnterpriseId(), value.demandCompany(), value.demandTitle(),
                value.scene(), value.supplierCompany(), value.solution(), value.score(),
                value.reasons(), value.state(), value.recommendedAt(),
                value.demandConfirmedAt(), value.candidateConfirmedAt(), value.closedReason(),
                value.version(), value.updatedAt(), actions);
    }

    private Set<String> allowedActions(PersistedMatchView value, ActorScope actor) {
        if (value.demandEnterpriseId().equals(value.candidateEnterpriseId())
                || !enterpriseLifecycle.isOperational(value.demandEnterpriseId())
                || !enterpriseLifecycle.isOperational(value.candidateEnterpriseId())) {
            return Set.of();
        }
        boolean participantEnterprise = actor.enterpriseId() != null
                && (actor.enterpriseId().equals(value.demandEnterpriseId())
                || actor.enterpriseId().equals(value.candidateEnterpriseId()));
        boolean demandEnterprise = value.demandEnterpriseId().equals(actor.enterpriseId());
        boolean enterpriseWriter = participantEnterprise
                && (actor.isEnterpriseAdmin() || actor.isAssociationStaff()
                || actor.isSystemAdmin());
        boolean owningAssociation = actor.associationId() != null
                && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId());
        boolean systemContext = actor.isSystemAdmin()
                && EcosystemScopeGuard.systemCanReadMatch(actor, value, catalogStore);
        boolean associationManager = owningAssociation
                && (actor.isAssociationStaff() || systemContext);
        boolean ownerManager = demandEnterprise && enterpriseWriter
                || associationManager && (!actor.isSystemAdmin() || actor.enterpriseId() == null
                || demandEnterprise);
        java.util.LinkedHashSet<String> actions = new java.util.LinkedHashSet<>();
        if (MatchLifecycle.PENDING_CONFIRMATION.equals(value.state())
                && owningAssociation
                && (actor.isAssociationReviewer() || actor.isSystemAdmin())) {
            actions.add("RECOMMEND");
        }
        if (List.of(MatchLifecycle.RECOMMENDED,
                MatchLifecycle.PARTIALLY_CONFIRMED).contains(value.state())
                && enterpriseWriter
                && (demandEnterprise && value.demandConfirmedAt() == null
                || value.candidateEnterpriseId().equals(actor.enterpriseId())
                && value.candidateConfirmedAt() == null)) {
            actions.add("CONFIRM");
        }
        if (MatchLifecycle.CONFIRMED.equals(value.state()) && ownerManager) {
            actions.add("INVITE");
        }
        if (MatchLifecycle.NEGOTIATING.equals(value.state())
                && (enterpriseWriter || associationManager)) {
            actions.add("NEGOTIATE");
        }
        if (List.of(MatchLifecycle.OUTCOME_PENDING, MatchLifecycle.CLOSED)
                .contains(value.state()) && participantEnterprise) {
            if (enterpriseWriter) {
                actions.add("FEEDBACK");
            }
        }
        if (MatchLifecycle.OUTCOME_PENDING.equals(value.state()) && ownerManager
                && canArchiveNow(value, actor)) {
            actions.add("ARCHIVE");
        }
        if (ownerManager && List.of(
                MatchLifecycle.PENDING_CONFIRMATION, MatchLifecycle.RECOMMENDED,
                MatchLifecycle.PARTIALLY_CONFIRMED, MatchLifecycle.CONFIRMED,
                MatchLifecycle.INVITED, MatchLifecycle.NEGOTIATING,
                MatchLifecycle.OUTCOME_PENDING).contains(value.state())) {
            actions.add("CLOSE");
        }
        return Set.copyOf(actions);
    }

    private boolean canArchiveNow(PersistedMatchView value, ActorScope actor) {
        if (workflowStore == null) {
            return false;
        }
        Set<UUID> successful = workflowStore.feedback(value.id(), actor).stream()
                .filter(feedback -> "SUCCESS".equals(feedback.outcome()))
                .map(MatchFeedbackView::enterpriseId)
                .collect(Collectors.toSet());
        return successful.contains(value.demandEnterpriseId())
                && successful.contains(value.candidateEnterpriseId())
                && !workflowStore.hasActiveOutcome(value.id(), actor);
    }

    private static PreconditionFailedException stale() {
        return new PreconditionFailedException("match version is stale; reload and retry with the latest ETag");
    }

    private List<OfferingView> visibleActiveOfferings(ActorScope actor) {
        List<OfferingView> active = new ArrayList<>();
        int page = 0;
        EcosystemPage<OfferingView> current;
        do {
            current = catalogService.offerings(actor, null, false, page, 100);
            current.items().stream()
                    .filter(offering -> "ACTIVE".equals(offering.status()) && !offering.disabled())
                    .forEach(active::add);
            page++;
        } while ((long) page * current.size() < current.total());
        return active;
    }

    private MatchCandidateDraft score(
            OfferingView offering,
            MemberProfile member,
            MatchRequest request,
            List<String> tags) {
        String offeringText = String.join(" ",
                offering.name(), offering.kind(), nullToEmpty(offering.description()),
                String.join(" ", offering.scenarios()), String.join(" ", offering.qualifications()))
                .toLowerCase(Locale.ROOT);
        String demandText = String.join(" ", request.demandTitle(), request.scene(),
                nullToEmpty(request.requirements())).toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        int score = 45;
        for (String tag : tags) {
            if (offeringText.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 12;
                reasons.add("已审核产品/服务命中标签：“" + tag + "”");
            }
        }
        for (String scenario : offering.scenarios()) {
            if (demandText.contains(scenario.toLowerCase(Locale.ROOT))) {
                score += 10;
                reasons.add("适用场景“" + scenario + "”与需求一致");
            }
        }
        if (request.requirements() != null && !request.requirements().isBlank()) {
            for (String requirement : request.requirements().split("\\s+")) {
                if (!requirement.isBlank() && offeringText.contains(requirement.toLowerCase(Locale.ROOT))) {
                    score += 8;
                    reasons.add("产品/服务资料覆盖需求能力“" + requirement + "”");
                }
            }
        }
        if (member != null) {
            for (String capability : member.capabilities()) {
                if (demandText.contains(capability.toLowerCase(Locale.ROOT))) {
                    score += 4;
                    reasons.add("企业履约能力“" + capability + "”可作为补充支撑");
                }
            }
            if (member.address() != null && member.address().contains("北京")) {
                score += 3;
                reasons.add("本地服务与交付条件较好");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("基于已审核产品/服务的行业与场景相关性推荐");
        }
        String supplier = offering.enterpriseName() != null && !offering.enterpriseName().isBlank()
                ? offering.enterpriseName()
                : member != null ? member.name() : offering.enterpriseId().toString();
        String solution = offering.description() == null || offering.description().isBlank()
                ? offering.name()
                : offering.name() + "：" + offering.description();
        return new MatchCandidateDraft(
                offering.enterpriseId(), supplier, solution, Math.min(score, 99), reasons);
    }

    private static MatchCandidateDraft betterCandidate(MatchCandidateDraft left, MatchCandidateDraft right) {
        if (left.score() != right.score()) {
            return left.score() > right.score() ? left : right;
        }
        int solutionOrder = left.solution().compareToIgnoreCase(right.solution());
        return solutionOrder <= 0 ? left : right;
    }

    private EcosystemMatch score(MemberProfile member, MatchRequest request, List<String> tags) {
        String memberText = String.join(" ", member.name(), nullToEmpty(member.category()),
                nullToEmpty(member.introduction()), String.join(" ", member.capabilities()),
                String.join(" ", member.products())).toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();
        int score = 55;
        for (String tag : tags) {
            if (memberText.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 10;
                reasons.add("企业能力命中标签：“" + tag + "”");
            }
        }
        for (String capability : member.capabilities()) {
            if (request.requirements() != null && request.requirements().contains(capability)) {
                score += 8;
                reasons.add("需求与能力“" + capability + "”直接匹配");
            }
        }
        if (member.address() != null && member.address().contains("北京")) {
            score += 5;
            reasons.add("本地服务与交付条件较好");
        }
        if (reasons.isEmpty()) {
            reasons.add("基于行业分类和企业资料的候选推荐");
        }
        String solution = member.products().isEmpty()
                ? String.join("、", member.capabilities())
                : String.join("、", member.products());
        return new EcosystemMatch(
                stableMatchId(member, request), request.demandCompany(), request.demandTitle(), request.scene(),
                member.name(), solution, Math.min(score, 99), reasons, "待确认", "刚刚");
    }

    private static String stableMatchId(MemberProfile member, MatchRequest request) {
        String supplierIdentity = member.unifiedSocialCreditCode() == null
                ? member.name() + "\u001e" + member.id()
                : member.unifiedSocialCreditCode();
        String identity = String.join("\u001f",
                canonical(request.demandCompany()),
                canonical(request.demandTitle()),
                canonical(request.scene()),
                canonical(request.requirements()),
                supplierIdentity);
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
