package com.guanxian.platform.bootstrap;

import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.collaboration.CollaborationView;
import com.guanxian.platform.ecosystem.EcosystemMatch;
import com.guanxian.platform.ecosystem.EcosystemMatchService;
import com.guanxian.platform.member.api.MemberDirectory;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardController {
    private final MemberDirectory memberDirectory;
    private final PolicyService policyService;
    private final EcosystemMatchService matchService;
    private final CollaborationService collaborationService;
    private final ActorScopeResolver actorScopeResolver;

    public DashboardController(
            MemberDirectory memberDirectory,
            PolicyService policyService,
            EcosystemMatchService matchService,
            CollaborationService collaborationService,
            ActorScopeResolver actorScopeResolver) {
        this.memberDirectory = memberDirectory;
        this.policyService = policyService;
        this.matchService = matchService;
        this.collaborationService = collaborationService;
        this.actorScopeResolver = actorScopeResolver;
    }

    @GetMapping("/association")
    @PreAuthorize("hasAuthority('DASHBOARD_ASSOCIATION_READ')")
    ApiResponse<AssociationDashboard> association(Authentication authentication) {
        ActorScope actor = actorScopeResolver.resolve(authentication);
        int memberCount = memberDirectory.findAll(null, actor).size();
        List<CollaborationView> collaborations = collaborationService.findAll();
        return ApiResponse.ok(new AssociationDashboard(
                List.of(
                        new Metric("会员企业", String.valueOf(memberCount), "当前演示数据", "info"),
                        new Metric("企业资料完整度", "82.6%", "较上月 +6.2%", "success"),
                        new Metric("本月有效匹配", "38", "其中 12 项沟通中", "warning"),
                        new Metric("待办协作事项", "3", "1 项将在本周到期", "danger")),
                List.of(
                        new Activity("A1", "AI 生成生态匹配建议", "涉及燃气安全、管线监测等场景", "18 分钟前", "match"),
                        new Activity("A2", "北京市地下管线信息管理办法更新", "已识别可能受影响的会员企业", "1 小时前", "policy"),
                        new Activity("A3", "会员企业资料待审核", "企业资料等待协会确认", "昨天 16:42", "member")),
                List.of(
                        new SceneDistribution("规划设计", 18, 64),
                        new SceneDistribution("施工建设", 27, 82),
                        new SceneDistribution("运行监测", 32, 96),
                        new SceneDistribution("更新改造", 21, 71),
                        new SceneDistribution("应急处置", 16, 58)),
                collaborations.stream().filter(item -> !"已完成".equals(item.stage())).limit(3).toList()));
    }

    @GetMapping("/enterprise")
    @PreAuthorize("hasAuthority('DASHBOARD_ENTERPRISE_READ')")
    ApiResponse<EnterpriseDashboard> enterprise() {
        List<PolicyView> policies = policyService.all();
        List<EcosystemMatch> matches = matchService.demoMatches();
        List<CollaborationView> collaborations = collaborationService.findAll();
        return ApiResponse.ok(new EnterpriseDashboard(
                91,
                List.of(
                        new Metric("在架产品/服务", "12", "2 项待补充技术参数", "info"),
                        new Metric("匹配商机", "8", "近 30 天新增 5 项", "success"),
                        new Metric("协作进行中", "3", "1 项等待我方反馈", "warning"),
                        new Metric("政策影响提醒", "4", "含 1 项即将施行", "danger")),
                policies.stream().limit(3).toList(),
                matches.stream().limit(2).toList(),
                collaborations.stream().limit(2).toList()));
    }

    record Metric(String label, String value, String change, String tone) {
    }

    record Activity(String id, String title, String detail, String time, String type) {
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
