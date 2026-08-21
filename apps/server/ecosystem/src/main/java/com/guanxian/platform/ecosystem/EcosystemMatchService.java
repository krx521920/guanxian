package com.guanxian.platform.ecosystem;

import com.guanxian.platform.ai.AiTextService;
import com.guanxian.platform.member.api.MemberDirectory;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class EcosystemMatchService {
    private final MemberDirectory memberDirectory;
    private final AiTextService aiTextService;

    public EcosystemMatchService(MemberDirectory memberDirectory, AiTextService aiTextService) {
        this.memberDirectory = memberDirectory;
        this.aiTextService = aiTextService;
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
