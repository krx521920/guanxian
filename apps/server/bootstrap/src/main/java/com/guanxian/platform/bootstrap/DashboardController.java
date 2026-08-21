package com.guanxian.platform.bootstrap;

import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.collaboration.CollaborationView;
import com.guanxian.platform.ecosystem.DemandView;
import com.guanxian.platform.ecosystem.EcosystemCatalogService;
import com.guanxian.platform.ecosystem.EcosystemMatch;
import com.guanxian.platform.ecosystem.EcosystemMatchService;
import com.guanxian.platform.ecosystem.OfferingView;
import com.guanxian.platform.ecosystem.PersistedMatchView;
import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.policy.PolicyService;
import com.guanxian.platform.policy.PolicyView;
import com.guanxian.platform.shared.api.ApiResponse;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardController {
    private final MemberDirectory memberDirectory;
    private final PolicyService policyService;
    private final EcosystemCatalogService catalogService;
    private final EcosystemMatchService matchService;
    private final CollaborationService collaborationService;
    private final ActorScopeResolver actorScopeResolver;

    public DashboardController(
            MemberDirectory memberDirectory,
            PolicyService policyService,
            EcosystemCatalogService catalogService,
            EcosystemMatchService matchService,
            CollaborationService collaborationService,
            ActorScopeResolver actorScopeResolver) {
        this.memberDirectory = memberDirectory;
        this.policyService = policyService;
        this.catalogService = catalogService;
        this.matchService = matchService;
        this.collaborationService = collaborationService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping("/association")
    @PreAuthorize("hasAuthority('DASHBOARD_ASSOCIATION_READ')")
    ApiResponse<AssociationDashboard> association(Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        List<MemberProfile> members = memberDirectory.findAll(null, actor);
        List<PolicyView> policies = policyService.findAll(null, actor);
        List<CollaborationView> collaborations = collaborationService.findAll(actor);
        List<OfferingView> offerings = catalogService.offerings(actor, null, false, 0, 100).items();
        List<DemandView> demands = catalogService.demands(actor, null, false, 0, 100).items();
        List<PersistedMatchView> matches = matches(demands, actor);
        long pending = collaborations.stream()
                .filter(item -> !"COMPLETED".equals(item.stage()) && !"DISABLED".equals(item.stage()))
                .count();

        return ApiResponse.ok(new AssociationDashboard(
                List.of(
                        new Metric("会员企业", String.valueOf(members.size()), "数据库实时统计", "info"),
                        new Metric("企业资料完整度", completeness(members) + "%", "按当前可见字段计算", "success"),
                        new Metric("有效匹配", String.valueOf(matches.size()), "基于已建档需求", "warning"),
                        new Metric("待办协作事项", String.valueOf(pending), "未完成且未停用", "danger")),
                activities(policies, collaborations, matches),
                sceneDistribution(offerings, demands),
                collaborations.stream()
                        .filter(item -> !"COMPLETED".equals(item.stage()) && !"DISABLED".equals(item.stage()))
                        .limit(3)
                        .toList()));
    }

    @GetMapping("/enterprise")
    @PreAuthorize("hasAuthority('DASHBOARD_ENTERPRISE_READ')")
    ApiResponse<EnterpriseDashboard> enterprise(Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        List<MemberProfile> members = memberDirectory.findAll(null, actor);
        List<PolicyView> policies = policyService.findAll(null, actor).stream()
                .filter(policy -> policy.publishDate() != null)
                .toList();
        List<CollaborationView> collaborations = collaborationService.findAll(actor);
        List<OfferingView> offerings = catalogService.offerings(actor, null, false, 0, 100).items();
        List<DemandView> demands = catalogService.demands(actor, null, false, 0, 100).items();
        List<PersistedMatchView> persistedMatches = matches(demands, actor);
        long activeCollaborations = collaborations.stream()
                .filter(item -> !"COMPLETED".equals(item.stage()) && !"DISABLED".equals(item.stage()))
                .count();
        long policyAlerts = policies.stream()
                .filter(policy -> "PUBLISHED".equals(policy.status()))
                .count();

        return ApiResponse.ok(new EnterpriseDashboard(
                completeness(members),
                List.of(
                        new Metric("在架产品/服务", String.valueOf(offerings.size()), "数据库实时统计", "info"),
                        new Metric("匹配商机", String.valueOf(persistedMatches.size()), "已持久化匹配", "success"),
                        new Metric("协作进行中", String.valueOf(activeCollaborations), "未完成且未停用", "warning"),
                        new Metric("政策影响提醒", String.valueOf(policyAlerts), "当前可见已发布政策", "danger")),
                policies.stream().limit(3).toList(),
                persistedMatches.stream().limit(5).map(DashboardController::legacyMatch).toList(),
                collaborations.stream()
                        .filter(item -> !"COMPLETED".equals(item.stage()) && !"DISABLED".equals(item.stage()))
                        .limit(5)
                        .toList()));
    }

    private List<PersistedMatchView> matches(List<DemandView> demands, ActorScope actor) {
        return demands.stream()
                .flatMap(demand -> matchService.persisted(demand.id(), actor).stream())
                .distinct()
                .sorted(Comparator.comparing(PersistedMatchView::updatedAt).reversed()
                        .thenComparing(PersistedMatchView::id))
                .toList();
    }

    private static List<Activity> activities(
            List<PolicyView> policies,
            List<CollaborationView> collaborations,
            List<PersistedMatchView> matches) {
        List<TimedActivity> values = new ArrayList<>();
        policies.forEach(policy -> values.add(new TimedActivity(
                policy.updatedAt(),
                new Activity("POLICY-" + policy.id(), policy.title(),
                        "政策状态：" + policy.status(), displayTime(policy.updatedAt()), "policy"))));
        collaborations.forEach(item -> values.add(new TimedActivity(
                item.updatedAt(),
                new Activity("COLLAB-" + item.id(), item.title(),
                        "协作阶段：" + item.stage(), displayTime(item.updatedAt()), "collaboration"))));
        matches.forEach(item -> values.add(new TimedActivity(
                item.updatedAt(),
                new Activity("MATCH-" + item.id(), item.demandTitle(),
                        "匹配企业：" + item.supplierCompany(), displayTime(item.updatedAt()), "match"))));
        return values.stream()
                .sorted(Comparator.comparing(TimedActivity::time, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(TimedActivity::activity)
                .toList();
    }

    private static List<SceneDistribution> sceneDistribution(
            List<OfferingView> offerings, List<DemandView> demands) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        offerings.stream().flatMap(item -> item.scenarios().stream())
                .forEach(scene -> counts.merge(scene, 1, Integer::sum));
        demands.stream().flatMap(item -> item.scenarios().stream())
                .forEach(scene -> counts.merge(scene, 1, Integer::sum));
        int maximum = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maximum == 0) {
            return List.of();
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8)
                .map(entry -> new SceneDistribution(
                        entry.getKey(), entry.getValue(), entry.getValue() * 100 / maximum))
                .toList();
    }

    private static int completeness(List<MemberProfile> members) {
        if (members.isEmpty()) {
            return 0;
        }
        return (int) Math.round(members.stream().mapToInt(DashboardController::profileCompleteness)
                .average().orElse(0));
    }

    private static int profileCompleteness(MemberProfile member) {
        int populated = 0;
        populated += present(member.name());
        populated += present(member.unifiedSocialCreditCode());
        populated += present(member.category());
        populated += present(member.address());
        populated += present(member.contactName());
        populated += present(member.contactPhone());
        populated += present(member.introduction());
        populated += member.capabilities().isEmpty() ? 0 : 1;
        populated += member.products().isEmpty() ? 0 : 1;
        populated += member.cooperationNeeds().isEmpty() ? 0 : 1;
        return populated * 10;
    }

    private static int present(String value) {
        return value == null || value.isBlank() ? 0 : 1;
    }

    private static EcosystemMatch legacyMatch(PersistedMatchView value) {
        return new EcosystemMatch(
                value.id().toString(), value.demandCompany(), value.demandTitle(), value.scene(),
                value.supplierCompany(), value.solution(), value.score(), value.reasons(),
                value.state(), displayTime(value.updatedAt()));
    }

    private static String displayTime(Instant value) {
        return value == null ? "" : value.toString();
    }

    record Metric(String label, String value, String change, String tone) {
    }

    record Activity(String id, String title, String detail, String time, String type) {
    }

    record TimedActivity(Instant time, Activity activity) {
    }

    record SceneDistribution(String name, int count, int percent) {
    }

    record AssociationDashboard(
            List<Metric> metrics,
            List<Activity> activities,
            List<SceneDistribution> sceneDistribution,
            List<CollaborationView> pendingTasks) {
    }

    record EnterpriseDashboard(
            int completeness,
            List<Metric> metrics,
            List<PolicyView> recommendedPolicies,
            List<EcosystemMatch> matches,
            List<CollaborationView> todo) {
    }
}
