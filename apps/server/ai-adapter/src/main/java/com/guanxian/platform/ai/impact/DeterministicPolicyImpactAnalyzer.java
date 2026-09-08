package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisDraft;
import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.AnalysisSource;
import com.guanxian.platform.policy.PolicyApplicability;
import com.guanxian.platform.policy.PolicyCandidateMatcher;
import com.guanxian.platform.policy.PolicyCandidateMatcher.Field;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Component
final class DeterministicPolicyImpactAnalyzer {
    AnalysisDraft analyze(AnalysisSource source) {
        var metadata = source.metadata();
        boolean summaryOnly = source.chunks().isEmpty();
        if (summaryOnly && (metadata.summary() == null || metadata.summary().isBlank())) {
            throw new PolicyImpactException(PolicyImpactException.Reason.EVIDENCE_REQUIRED,
                    "政策缺少已归档原文及可追溯摘要，无法生成分析");
        }
        var enterprise = List.of(new Field("企业业务资料", source.enterpriseProfile()));
        var chunks = source.chunks().stream().sorted(Comparator
                .<PolicyImpactAnalysisStore.SourceChunk>comparingInt(chunk -> PolicyCandidateMatcher.sharedEvidence(
                        List.of(new Field("政策原文", chunk.content())), enterprise).size()).reversed()
                .thenComparing(PolicyImpactAnalysisStore.SourceChunk::id)).limit(5).toList();
        var fields = summaryOnly ? List.of(new Field("政策摘要（非原文）", metadata.summary()))
                : chunks.stream().map(chunk -> new Field("政策原文", chunk.content())).toList();
        var matches = PolicyCandidateMatcher.sharedEvidence(fields, enterprise);
        var topics = matches.stream().map(PolicyCandidateMatcher.Evidence::topic).distinct().toList();
        // Obligation words never increase relevance. Summaries cannot yield a high-impact conclusion.
        String level = topics.isEmpty() ? "LOW" : topics.size() >= 2 && !summaryOnly ? "HIGH" : "MEDIUM";
        var assessment = PolicyApplicability.assess(String.join("\n", fields.stream().map(Field::text).toList()),
                metadata.audience(), metadata.region(), metadata.effectiveOn(), metadata.sourceStatus(),
                LocalDate.now(ZoneId.of("Asia/Shanghai")), enterprise);
        if (assessment.checks().stream().anyMatch(check -> "OBSOLETE_AT_SOURCE".equals(check.state())
                || "NOT_YET_EFFECTIVE".equals(check.state()))) level = "LOW";
        if ("HIGH".equals(level) && ("INDIRECT_BUSINESS_CLUE".equals(assessment.kind())
                || "INDIRECT_OPPORTUNITY".equals(assessment.kind()))) level = "MEDIUM";
        var references = summaryOnly ? List.of(new PolicyAnalysisEvidence.Reference("POLICY_SUMMARY",
                source.policyDocumentId(), source.policyTitle(), metadata.sourceUrl(), metadata.summary()))
                : chunks.stream().map(chunk -> new PolicyAnalysisEvidence.Reference("KNOWLEDGE_CHUNK", chunk.id(),
                        source.policyTitle(), metadata.sourceUrl(), chunk.content())).toList();
        var details = new PolicyAnalysisEvidence(summaryOnly ? "SUMMARY_REFERENCE" : "SOURCE_CHUNKS",
                metadata.policyVersion(), metadata.enterpriseVersion(), Instant.now(), references, assessment);
        String summary = (summaryOnly ? "摘要参考分析（非原文，不可审核发布）：" : "原文线索分析：")
                + "《" + source.policyTitle() + "》与“" + source.enterpriseName() + "”"
                + (topics.isEmpty() ? "未发现具体业务主题交集。" : "共享业务主题：" + String.join("、", topics) + "。")
                + "关注程度 " + level + " 只代表主题线索，不代表法定适用或合规结论。"
                + String.join("；", assessment.checks().stream().map(check -> check.dimension() + "：" + check.explanation()).toList())
                + (summaryOnly ? "。须补归档原文后重新分析，再进行人工审核。" : "。需结合完整原文及企业实际情况人工核验。");
        return new AnalysisDraft(source.policyDocumentId(), source.policyTitle(), source.enterpriseId(),
                source.enterpriseName(), source.associationId(), level, summary,
                chunks.stream().map(PolicyImpactAnalysisStore.SourceChunk::id).toList(), details);
    }
}
