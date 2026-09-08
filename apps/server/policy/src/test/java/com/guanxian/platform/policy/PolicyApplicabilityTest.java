package com.guanxian.platform.policy;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
class PolicyApplicabilityTest {
    @Test void governmentDutiesDoNotBecomeEnterpriseObligations() {
        var value = PolicyApplicability.assess("政府应当推进供水改造","政府主管部门","全国",null,null,LocalDate.of(2026,9,7));
        assertThat(value.kind()).isEqualTo("INDIRECT_OPPORTUNITY");
        assertThat(value.applicability()).isEqualTo("UNVERIFIED");
        assertThat(value.checks()).anyMatch(c -> c.state().equals("GOVERNMENT_SCOPE"));
    }
    @Test void nonMandatoryStandardIsNotTurnedIntoCompliance() {
        var value = PolicyApplicability.assess("推荐性标准不等于强制，支持监测建设","企业","北京",null,null,LocalDate.of(2026,9,7));
        assertThat(value.kind()).isEqualTo("BUSINESS_OPPORTUNITY");
    }
    @Test void mixedCluesAreExplicitlySeparatedAndPastDateNeverMeansCurrentlyValid() {
        var value = PolicyApplicability.assess("企业应当巡检，支持设备改造","企业","北京",LocalDate.of(2020,1,1),"有效",LocalDate.of(2026,9,7));
        assertThat(value.kind()).isEqualTo("MIXED_CLUES");
        assertThat(value.checks()).anyMatch(c -> c.state().equals("RECHECK_REQUIRED"));
    }
    @Test void recommendationDoesNotEraseSeparateMandatoryClause() {
        var value = PolicyApplicability.assess("推荐性标准支持设备改造；运营单位必须记录巡检情况", "企业", "北京", null, null, LocalDate.now());
        assertThat(value.kind()).isEqualTo("MIXED_CLUES");
        assertThat(value.checks()).anyMatch(c -> c.explanation().contains("必须逐条核验"));
    }
    @Test void negativeWordingDoesNotInventComplianceOrOpportunity() {
        var value = PolicyApplicability.assess("无需强制性认证，不要求必须采用该方案，不支持补贴", "企业", "北京", null, null, LocalDate.now());
        assertThat(value.kind()).isEqualTo("RELATED_TOPIC");
    }
    @Test void notRepealedAndPartiallyRepealedAreNotWholePolicyRepeals() {
        for (String text : java.util.List.of("未废止", "现行（未废止）", "部分条款废止")) {
            var value = PolicyApplicability.assess("供水", "企业", "北京", LocalDate.of(2020,1,1), text, LocalDate.now());
            assertThat(value.checks()).noneMatch(c -> c.state().equals("OBSOLETE_AT_SOURCE"));
        }
    }
    @Test void enterpriseRoleEvidenceIsTraceableButNeverProofOfEligibility() {
        var fields = java.util.List.of(new PolicyCandidateMatcher.Field("企业业务角色", "供水运营服务"));
        var value = PolicyApplicability.assess("供水企业应当巡检", "供水运营企业", "北京", null, null, LocalDate.now(), fields);
        assertThat(value.applicability()).isEqualTo("UNVERIFIED");
        assertThat(value.checks()).anyMatch(c -> c.state().equals("ROLE_CLUE_FOUND") && c.explanation().contains("不代表资质已核验"));
        var negative = PolicyApplicability.assess("应当巡检", "供水运营企业", "北京", null, null, LocalDate.now(),
                java.util.List.of(new PolicyCandidateMatcher.Field("简介", "不提供运营服务")));
        assertThat(negative.checks()).noneMatch(c -> c.state().equals("ROLE_CLUE_FOUND"));
    }
    @Test void administrativeUnitsAreNotEnterpriseUnits() {
        var value = PolicyApplicability.assess("主管部门应当建设监测平台", "政府主管部门及相关行政机关、事业单位", "全国", null, null, LocalDate.now());
        assertThat(value.kind()).isEqualTo("INDIRECT_OPPORTUNITY");
    }
}
