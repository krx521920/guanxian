package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisDraft;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisSource;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.SourceChunk;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
final class DeterministicPolicyImpactAnalyzer {
    private static final List<String> DOMAIN_KEYWORDS = List.of(
            "燃气", "天然气", "供水", "自来水", "排水", "污水", "热力", "供热",
            "矿山", "阀门", "球阀", "蝶阀", "管线", "管道", "施工", "地勘",
            "监测", "报警", "泄漏", "巡检", "数字孪生", "信息汇交", "环境治理",
            "安全", "应急", "测绘", "勘察", "检测", "修复", "运维", "数据");
    private static final List<String> OBLIGATION_KEYWORDS = List.of(
            "应当", "必须", "不得", "禁止", "要求", "责任", "期限", "报送",
            "汇交", "检查", "巡检", "记录", "标准", "整改", "处罚", "风险", "隐患");

    AnalysisDraft analyze(AnalysisSource source) {
        if (source.chunks().isEmpty()) {
            throw new PolicyImpactException(
                    PolicyImpactException.Reason.EVIDENCE_REQUIRED,
                    "no published knowledge chunks are linked to this policy");
        }
        String profile = normalize(source.enterpriseProfile());
        Set<String> enterpriseKeywords = new LinkedHashSet<>();
        for (String keyword : DOMAIN_KEYWORDS) {
            if (profile.contains(keyword.toLowerCase(Locale.ROOT))) {
                enterpriseKeywords.add(keyword);
            }
        }

        List<ScoredChunk> ranked = source.chunks().stream()
                .map(chunk -> score(chunk, enterpriseKeywords))
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed()
                        .thenComparing(chunk -> chunk.chunk().id()))
                .toList();
        List<ScoredChunk> evidence = ranked.stream().limit(5).toList();
        Set<String> matchedKeywords = new LinkedHashSet<>();
        int obligations = 0;
        for (ScoredChunk chunk : evidence) {
            matchedKeywords.addAll(chunk.matches());
            obligations += chunk.obligations();
        }

        String level;
        if (matchedKeywords.size() >= 2 || (matchedKeywords.size() == 1 && obligations >= 4)) {
            level = "HIGH";
        } else if (!matchedKeywords.isEmpty() || obligations >= 2) {
            level = "MEDIUM";
        } else {
            level = "LOW";
        }
        List<UUID> evidenceIds = evidence.stream().map(item -> item.chunk().id()).toList();
        String summary = summary(source, level, matchedKeywords, obligations, evidenceIds.size());
        return new AnalysisDraft(
                source.policyDocumentId(), source.policyTitle(), source.enterpriseId(), source.enterpriseName(),
                source.associationId(), level, summary, evidenceIds);
    }

    private static ScoredChunk score(SourceChunk chunk, Set<String> enterpriseKeywords) {
        String content = normalize(chunk.content());
        Set<String> matches = new LinkedHashSet<>();
        for (String keyword : enterpriseKeywords) {
            if (content.contains(keyword.toLowerCase(Locale.ROOT))) {
                matches.add(keyword);
            }
        }
        int obligations = 0;
        for (String keyword : OBLIGATION_KEYWORDS) {
            if (content.contains(keyword)) {
                obligations++;
            }
        }
        return new ScoredChunk(chunk, matches.size() * 10 + obligations, Set.copyOf(matches), obligations);
    }

    private static String summary(
            AnalysisSource source, String level, Set<String> matches, int obligations, int evidenceCount) {
        String relation = switch (level) {
            case "HIGH" -> "直接相关，建议优先核验合规与实施安排";
            case "MEDIUM" -> "存在相关要求，建议由业务负责人进一步核验";
            default -> "当前可见材料中的直接关联较弱，仍需关注后续细则";
        };
        String features = matches.isEmpty() ? "未命中特定业务关键词" : "命中企业特征：" + String.join("、", matches);
        return "%s《%s》与企业“%s”%s；%s；识别到%d个义务/风险词，引用%d个已入库片段。本结果由确定性词法规则生成，需经协会审核。"
                .formatted(level + "：", source.policyTitle(), source.enterpriseName(), relation,
                        features, obligations, evidenceCount);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record ScoredChunk(SourceChunk chunk, int score, Set<String> matches, int obligations) {
    }
}
