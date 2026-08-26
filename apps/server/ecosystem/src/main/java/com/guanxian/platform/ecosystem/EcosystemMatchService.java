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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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

    public List<EcosystemMatch> demoMatches() {
        return List.of(
                new EcosystemMatch("M001", "北京市政建设集团", "高压燃气管道零泄漏阀门采购", "燃气管网 · 更新改造",
                        "北方阀门制造有限公司", "智能零泄漏球阀及远程控制方案", 94,
                        List.of("介质与压力等级匹配", "具备同类产品能力", "北京周边可快速交付"), "沟通中", "今天 10:30"),
                new EcosystemMatch("M002", "首都城市更新发展有限公司", "老旧街区地下管线综合探测", "城市更新 · 探测测绘",
                        "京城管网科技有限公司", "多源监测与三维管线建模服务", 88,
                        List.of("城市更新场景匹配", "具备数字孪生能力", "服务覆盖北京地区"), "已推荐", "昨天 16:18"));
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
        List<MatchCandidateDraft> candidates = memberDirectory.findAll(null, actor).stream()
                .filter(member -> !member.id().equals(demand.enterpriseId()))
                .map(member -> toDraft(member, score(member, request, tags)))
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
        return matchStore.list(demandId, actor);
    }

    @Transactional
    public PersistedMatchView recommend(UUID id, long expectedVersion, ActorScope actor) {
        if (!actor.isSystemAdmin() && !actor.isAssociationReviewer()) {
            throw new ForbiddenException("MATCH_REVIEWER_REQUIRED", "association reviewer identity is required");
        }
        PersistedMatchView current = find(id, actor);
        requireState(current.state(), Set.of("PENDING_CONFIRMATION", "CONFIRMED"));
        return transition(current, expectedVersion, "RECOMMENDED", null, "RECOMMEND", actor);
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
        requireState(current.state(), Set.of("PENDING_CONFIRMATION", "RECOMMENDED"));
        return transition(current, expectedVersion, "CONFIRMED", null, "CONFIRM", actor);
    }

    @Transactional
    public PersistedMatchView close(
            UUID id, long expectedVersion, MatchCloseRequest request, ActorScope actor) {
        PersistedMatchView current = find(id, actor);
        if (!actor.isSystemAdmin() && !actor.isAssociationStaff()
                && !current.demandEnterpriseId().equals(actor.enterpriseId())) {
            throw new ForbiddenException(
                    "MATCH_CLOSE_FORBIDDEN", "only the demand owner or association can close the match");
        }
        if ("CLOSED".equals(current.state())) {
            throw new PreconditionFailedException("match is already closed");
        }
        return transition(current, expectedVersion, "CLOSED", request.reason(), "CLOSE", actor);
    }

    private PersistedMatchView find(UUID id, ActorScope actor) {
        return matchStore.find(id, actor).orElseThrow(() -> new NotFoundException("ecosystem match", id));
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

    private static void requireDemandOwnerOrAssociation(DemandView demand, ActorScope actor) {
        if (actor.isSystemAdmin() || actor.isAssociationStaff()
                || demand.enterpriseId().equals(actor.enterpriseId())) {
            return;
        }
        throw new ForbiddenException(
                "DEMAND_SCOPE_VIOLATION", "only the demand owner or association can generate matches");
    }

    private static void requireState(String state, Set<String> allowed) {
        if (!allowed.contains(state)) {
            throw new PreconditionFailedException(
                    "match state " + state + " does not allow this operation");
        }
    }

    private static PreconditionFailedException stale() {
        return new PreconditionFailedException("match version is stale; reload and retry with the latest ETag");
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

    private static MatchCandidateDraft toDraft(MemberProfile member, EcosystemMatch value) {
        return new MatchCandidateDraft(
                member.id(), value.supplierCompany(), value.solution(), value.score(), value.reasons());
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
