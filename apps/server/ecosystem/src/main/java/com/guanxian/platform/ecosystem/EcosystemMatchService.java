package com.guanxian.platform.ecosystem;

import com.guanxian.platform.ai.AiTextService;
import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.error.NotFoundException;
import com.guanxian.platform.shared.error.PreconditionFailedException;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EcosystemMatchService {
    private final MemberDirectory memberDirectory;
    private final AiTextService aiTextService;
    private final EcosystemCatalogService catalogService;
    private final EcosystemMatchStore matchStore;
    private final EcosystemCatalogStore catalogStore;

    public EcosystemMatchService(
            MemberDirectory memberDirectory,
            AiTextService aiTextService,
            EcosystemCatalogService catalogService,
            EcosystemMatchStore matchStore,
            EcosystemCatalogStore catalogStore) {
        this.memberDirectory = memberDirectory;
        this.aiTextService = aiTextService;
        this.catalogService = catalogService;
        this.matchStore = matchStore;
        this.catalogStore = catalogStore;
    }

    @Transactional(readOnly = true)
    public List<PersistedMatchView> persisted(ActorScope actor) {
        return matchStore.list(actor).stream().filter(value -> canReadMatch(value, actor)).toList();
    }

    public List<EcosystemMatch> match(MatchRequest request, ActorScope actor) {
        int limit = request.limit() == null ? 5 : request.limit();
        String context = String.join(" ", request.demandTitle(), request.scene(), nullToEmpty(request.requirements()));
        List<String> tags = aiTextService.extractTags(context);
        return memberDirectory.findAll(null, actor).stream()
                .filter(member -> !member.name().equalsIgnoreCase(request.demandCompany()))
                .map(member -> score(member, request, tags))
                .sorted(Comparator.comparingInt(EcosystemMatch::score).reversed()
                        .thenComparing(EcosystemMatch::supplierCompany)
                        .thenComparing(EcosystemMatch::id))
                .limit(limit)
                .toList();
    }

    @Transactional
    public List<PersistedMatchView> generate(UUID demandId, Integer requestedLimit, ActorScope actor) {
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
        return persisted;
    }

    @Transactional(readOnly = true)
    public List<PersistedMatchView> persisted(UUID demandId, ActorScope actor) {
        catalogService.demand(demandId, actor, false);
        return matchStore.list(demandId, actor).stream().filter(value -> canReadMatch(value, actor)).toList();
    }

    @Transactional
    public PersistedMatchView recommend(UUID id, long expectedVersion, ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            throw new ForbiddenException("MATCH_REVIEWER_REQUIRED", "association reviewer identity is required");
        }
        PersistedMatchView current = find(id, actor);
        requireOwningAssociation(current.demandEnterpriseId(), actor);
        MatchLifecycle.requireRecommendationAllowed(current);
        if (current.version() != expectedVersion) {
            throw stale();
        }
        PersistedMatchView updated = matchStore.recommend(current.id(), expectedVersion, actor)
                .orElseThrow(EcosystemMatchService::stale);
        catalogStore.recordChange(
                actor, "RECOMMEND", "ECOSYSTEM_MATCH", updated.id(),
                actor.associationId(), updated.demandEnterpriseId(), updated.version(), updated);
        return updated;
    }

    @Transactional
    public PersistedMatchView confirm(UUID id, long expectedVersion, ActorScope actor) {
        PersistedMatchView current = find(id, actor);
        if (actor.enterpriseId() == null
                || (!actor.enterpriseId().equals(current.demandEnterpriseId())
                && !actor.enterpriseId().equals(current.candidateEnterpriseId()))) {
            throw new ForbiddenException(
                    "MATCH_PARTICIPANT_REQUIRED", "only an enterprise participating in the match can confirm it");
        }
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
        return updated;
    }

    @Transactional
    public PersistedMatchView close(
            UUID id, long expectedVersion, MatchCloseRequest request, ActorScope actor) {
        PersistedMatchView current = find(id, actor);
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(
                current.demandEnterpriseId(), actor.associationId());
        if (!actor.isSystemAdmin() && !owningAssociation
                && !current.demandEnterpriseId().equals(actor.enterpriseId())) {
            throw new ForbiddenException(
                    "MATCH_CLOSE_FORBIDDEN", "only the demand owner or association can close the match");
        }
        MatchLifecycle.requireClosable(current);
        return transition(
                current, expectedVersion, MatchLifecycle.CLOSED, request.reason(), "CLOSE", actor);
    }

    private PersistedMatchView find(UUID id, ActorScope actor) {
        return matchStore.find(id, actor).filter(value -> canReadMatch(value, actor))
                .orElseThrow(() -> new NotFoundException("ecosystem match", id));
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
        return updated;
    }

    private void requireDemandOwnerOrAssociation(DemandView demand, ActorScope actor) {
        boolean owningAssociation = actor.isAssociationStaff()
                && catalogStore.enterpriseBelongsToAssociation(demand.enterpriseId(), actor.associationId());
        if (actor.isSystemAdmin() || owningAssociation || demand.enterpriseId().equals(actor.enterpriseId())) {
            return;
        }
        throw new ForbiddenException(
                "DEMAND_SCOPE_VIOLATION", "only the demand owner or association can generate matches");
    }

    private void requireOwningAssociation(UUID enterpriseId, ActorScope actor) {
        if (!actor.isSystemAdmin()
                && (!actor.isAssociationStaff()
                || !catalogStore.enterpriseBelongsToAssociation(enterpriseId, actor.associationId()))) {
            throw new ForbiddenException(
                    "ASSOCIATION_SCOPE_VIOLATION", "association can only manage matches owned by its members");
        }
    }

    private boolean canReadMatch(PersistedMatchView value, ActorScope actor) {
        return actor.isSystemAdmin()
                || value.demandEnterpriseId().equals(actor.enterpriseId())
                || value.candidateEnterpriseId().equals(actor.enterpriseId())
                || actor.isAssociationStaff() && catalogStore.enterpriseBelongsToAssociation(
                value.demandEnterpriseId(), actor.associationId());
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
        String memberText = String.join(" ", member.name(), member.category(),
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
