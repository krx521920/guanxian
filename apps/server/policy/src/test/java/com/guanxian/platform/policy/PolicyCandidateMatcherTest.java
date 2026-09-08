package com.guanxian.platform.policy;

import com.guanxian.platform.policy.PolicyCandidateMatcher.Enterprise;
import com.guanxian.platform.policy.PolicyCandidateMatcher.Metadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCandidateMatcherTest {
    private static final Metadata NONE = new Metadata(null, null, null, null);
    private static PolicyView policy(String summary) { return policy(summary, null); }
    private static PolicyView policy(String summary, LocalDate effective) {
        return new PolicyView(UUID.randomUUID().toString(), "测试政策", null, null, null, null,
                null, effective, null, "PUBLISHED", summary, List.of(), UUID.randomUUID(), "MEMBERS", 0, false, false, Instant.EPOCH);
    }
    private static Enterprise enterprise(String text) {
        return new Enterprise(UUID.randomUUID(), "测试企业", null, text, "[]", "[]", "[]", 7);
    }

    @Test void emptyEvidenceIsNotAnInapplicabilityConclusion() {
        assertThat(PolicyCandidateMatcher.match(policy(null), NONE, enterprise(null))).isEmpty();
        assertThat(PolicyCandidateMatcher.match(policy("燃气安全"), NONE, enterprise("食品经营"))).isEmpty();
    }
    @Test void obligationsAndGenericWordsCannotCreateCandidates() {
        assertThat(PolicyCandidateMatcher.match(policy("管线管道施工必须符合安全标准，要求记录数据并履行责任"),
                NONE, enterprise("管线管道安全施工数据"))).isEmpty();
    }
    @Test void synonymsHaveNamedEvidenceButDoNotInflateCounts() {
        var result = PolicyCandidateMatcher.match(policy("供水与自来水监测"), NONE, enterprise("给水、传感器服务")).orElseThrow();
        assertThat(result.matchedTopics()).isEqualTo(2);
        assertThat(result.relevance()).isEqualTo("MULTIPLE_CLUES");
        assertThat(result.evidence()).extracting(PolicyCandidateMatcher.Evidence::topic).containsExactly("供水", "监测与预警");
        assertThat(result.evidence().getFirst().policyField()).isEqualTo("政策摘要");
        assertThat(result.evidence().getFirst().enterpriseTerm()).isEqualTo("给水");
        assertThat(result.enterpriseVersion()).isEqualTo(7);
    }
    @Test void oneTopicIsLimitedEvidenceNotHighImpact() {
        var result = PolicyCandidateMatcher.match(policy("燃气天然气必须履行责任，要求报送记录"), NONE,
                enterprise("天然气燃气燃气燃气")).orElseThrow();
        assertThat(result.relevance()).isEqualTo("LIMITED_CLUES");
        assertThat(result.matchedTopics()).isEqualTo(1);
    }
    @Test void companyNameAloneIsNotEvidence() {
        var e = new Enterprise(UUID.randomUUID(), "燃气监测测试公司", "咨询", "", "[]", "[]", "[]", 0);
        assertThat(PolicyCandidateMatcher.match(policy("燃气监测"), NONE, e)).isEmpty();
    }
    @Test void asciiAcronymsMustHaveWordBoundaries() {
        assertThat(PolicyCandidateMatcher.match(policy("GIS"), NONE, enterprise("logistics"))).isEmpty();
        assertThat(PolicyCandidateMatcher.match(policy("GIS"), NONE, enterprise("GIS平台"))).isPresent();
    }
    @Test void obviousNegatedCapabilitiesAreNotPositiveEvidence() {
        for (String text : List.of("不涉及燃气", "不从事燃气", "不提供燃气", "未开展燃气", "无燃气业务")) {
            assertThat(PolicyCandidateMatcher.match(policy("燃气"), NONE, enterprise(text))).as(text).isEmpty();
        }
        assertThat(PolicyCandidateMatcher.match(policy("不适用燃气"), NONE, enterprise("燃气"))).isEmpty();
    }
    @Test void sourceAudienceIsAnExplicitClueNotAnEligibilityVerdict() {
        var metadata = new Metadata("", "适用供水运营单位", "北京市", "2026-09-05");
        var result = PolicyCandidateMatcher.match(policy("资料待补充"), metadata, enterprise("给水服务")).orElseThrow();
        assertThat(result.evidence().getFirst().policyField()).isEqualTo("来源适用对象");
        assertThat(result.missingEvidence()).anyMatch(s -> s.contains("主体身份"));
        assertThat(result.missingEvidence()).anyMatch(s -> s.contains("项目所在地"));
    }
    @Test void profileChangesAreUsedWithoutPersistedStaleMatches() {
        var p = policy("监测与物联网");
        assertThat(PolicyCandidateMatcher.match(p, NONE, enterprise("物联网监测"))).isPresent();
        assertThat(PolicyCandidateMatcher.match(p, NONE, enterprise("食品"))).isEmpty();
    }
    @Test void datesDoNotMasqueradeAsCurrentLegalValidity() {
        LocalDate today = LocalDate.of(2026, 9, 7);
        assertThat(PolicyCandidateMatcher.policyGaps(policy(""), NONE, today)).anyMatch(s -> s.contains("施行日期缺失"));
        assertThat(PolicyCandidateMatcher.policyGaps(policy("", today.plusDays(1)), NONE, today)).anyMatch(s -> s.contains("尚未到"));
        assertThat(PolicyCandidateMatcher.policyGaps(policy("", today.minusDays(1)), NONE, today)).anyMatch(s -> s.contains("废止"));
    }
    @Test void disjointKnownIndustriesDoNotMatchOnMonitoringAlone() {
        assertThat(PolicyCandidateMatcher.match(policy("燃气监测预警"), NONE, enterprise("污水监测预警"))).isEmpty();
        assertThat(PolicyCandidateMatcher.match(policy("燃气监测"), NONE, enterprise("传感器设备制造"))).isPresent();
        assertThat(PolicyCandidateMatcher.match(policy("燃气监测"), NONE, enterprise("燃气及污水监测"))).isPresent();
    }
    @Test void storedBusinessRolesParticipateAndDifferentActorsRemainIndirectClues() {
        var company = new Enterprise(UUID.randomUUID(), "设备厂", null, "监测传感器", "[]", "[]", "[]", 4, "[\"设备制造\"]");
        var value = PolicyCandidateMatcher.match(policy("燃气运营单位应当监测"),
                new Metadata(null, "燃气运营单位", "北京", null), company).orElseThrow();
        assertThat(value.assessment().kind()).isEqualTo("INDIRECT_BUSINESS_CLUE");
        assertThat(value.assessment().checks()).anyMatch(c -> c.state().equals("DIFFERENT_ROLE_CLUE"));
    }
    @Test void indirectSupplierDoesNotOutrankARoleMatchedEnterpriseOnTopicCountAlone() {
        var p = policy("燃气监测运营企业应当检查");
        var meta = new Metadata(null, "燃气运营企业", "北京", null);
        var supplier = PolicyCandidateMatcher.match(p, meta, enterprise("燃气监测设备制造")).orElseThrow();
        var operator = PolicyCandidateMatcher.match(p, meta, enterprise("燃气运营服务")).orElseThrow();
        assertThat(List.of(supplier, operator).stream().sorted(PolicyCandidateMatcher.candidateOrder()).toList())
                .containsExactly(operator, supplier);
    }
}
