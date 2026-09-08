package com.guanxian.platform.ai.impact;

import com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PolicySummaryAnalysisTest {
    final UUID policy = UUID.randomUUID(), enterprise = UUID.randomUUID(), association = UUID.randomUUID();
    final ImpactActor admin = new ImpactActor(null,"tester","tester",association,null,false,true,true);
    final MemoryPolicyImpactAnalysisStore store = new MemoryPolicyImpactAnalysisStore();
    final PolicyImpactAnalysisService service = new PolicyImpactAnalysisService(store,new DeterministicPolicyImpactAnalyzer());
    AnalysisSource source(String summary, String profile, String audience, LocalDate date, String status, long version, List<SourceChunk> chunks) {
        return new AnalysisSource(policy,"测试政策",enterprise,"燃气监测公司名称不能用于匹配",association,profile,chunks,
                new PolicyAnalysisEvidence.Metadata(summary,"https://example.test/source",version,2,audience,"北京市",date,status));
    }
    @Test void summaryEvidencePersistsWithoutInventingChunksAndCannotBeApproved() {
        store.putSource(source("燃气运营单位应当监测泄漏风险","燃气监测","燃气运营单位",null,null,3,List.of()));
        var created = service.create(policy,enterprise,admin);
        assertTrue(created.evidenceChunkIds().isEmpty());
        assertEquals("MEDIUM", created.impactLevel());
        assertEquals("SUMMARY_REFERENCE", created.evidenceDetails().basis());
        assertEquals(3, created.evidenceDetails().policyVersion());
        assertEquals("POLICY_SUMMARY", created.evidenceDetails().references().getFirst().kind());
        assertTrue(created.evidenceDetails().references().getFirst().quote().contains("燃气运营单位"));
        assertEquals(created.evidenceDetails(), service.get(created.id(),admin).evidenceDetails());
        assertTrue(assertThrows(PolicyImpactException.class, () -> service.review(created.id(),0,true,null,admin)).getMessage().contains("摘要参考"));
        assertEquals(1, store.history(created.id(),10).size());
        assertEquals(created.evidenceDetails(), service.review(created.id(),0,false,"补原文",admin).evidenceDetails());
    }
    @Test void fullTextUpgradeAndSourceChangesRequireReanalysisBeforeApproval() {
        String text = "燃气运营单位应当监测泄漏风险";
        store.putSource(source(text,"燃气监测",null,null,null,1,List.of()));
        var summary = service.create(policy,enterprise,admin);
        UUID chunk = UUID.randomUUID();
        store.putSource(source(text,"燃气监测",null,null,null,1,List.of(new SourceChunk(chunk,text))));
        var full = service.reanalyze(summary.id(),0,admin);
        assertEquals("SOURCE_CHUNKS", full.evidenceDetails().basis());
        assertEquals(List.of(chunk), full.evidenceChunkIds());
        store.putSource(source(text,"燃气监测",null,null,null,2,List.of(new SourceChunk(chunk,text))));
        assertTrue(assertThrows(PolicyImpactException.class, () -> service.review(full.id(),1,true,null,admin)).getMessage().contains("已变化"));
        var refreshed = service.reanalyze(full.id(),1,admin);
        assertEquals("APPROVED", service.review(full.id(),refreshed.version(),true,null,admin).status());
    }
    @Test void obligationWordsAndCompanyNameNeverCreateBusinessRelevance() {
        store.putSource(source("燃气监测单位应当必须不得违反标准，记录整改责任","食品经营 管线 施工 安全 数据",null,null,null,1,List.of()));
        var value = service.create(policy,enterprise,admin);
        assertEquals("LOW", value.impactLevel());
        assertTrue(value.summary().contains("未发现具体业务主题交集"));
    }
    @Test void sourceValidityCapsAttentionAndRegionRequiresActualProjectEvidence() {
        for (String status : List.of("已废止","现行")) {
            var date = status.equals("现行") ? LocalDate.now().plusYears(2) : LocalDate.of(2020,1,1);
            var draft = new DeterministicPolicyImpactAnalyzer().analyze(source("应当燃气监测","燃气监测","燃气运营单位",date,status,1,
                    List.of(new SourceChunk(UUID.randomUUID(),"应当燃气监测"))));
            assertEquals("LOW", draft.impactLevel());
            assertEquals("UNVERIFIED", draft.evidenceDetails().assessment().applicability());
            assertTrue(draft.summary().contains("具体项目所在地"));
        }
    }
    @Test void noSummaryOrFullTextFailsInsteadOfFabricatingEvidence() {
        store.putSource(source(null,"燃气监测",null,null,null,0,List.of()));
        assertTrue(assertThrows(PolicyImpactException.class, () -> service.create(policy,enterprise,admin)).getMessage().contains("缺少"));
        assertEquals(0, store.count(admin.readScope(),null,null,null));
    }
    @Test void removedOriginalChunkBlocksApproval() {
        store.putSource(source("摘要","供水监测",null,null,null,1,List.of(new SourceChunk(UUID.randomUUID(),"供水监测"))));
        var original = service.create(policy,enterprise,admin);
        store.putSource(source("摘要","供水监测",null,null,null,1,List.of()));
        assertTrue(assertThrows(PolicyImpactException.class, () -> service.review(original.id(),0,true,null,admin)).getMessage().contains("已变化"));
    }
    @Test void fullTextDoesNotUpgradeUnrelatedIndustryToHighImpact() {
        var result = new DeterministicPolicyImpactAnalyzer().analyze(source("燃气监测预警", "污水监测预警", "燃气运营企业", null, null, 1,
                List.of(new SourceChunk(UUID.randomUUID(), "燃气企业必须监测预警并依法报告"))));
        assertEquals("LOW", result.impactLevel());
    }
    @Test void changedTextUnderTheSameChunkIdRequiresReanalysis() {
        UUID chunk = UUID.randomUUID();
        store.putSource(source("摘要", "供水监测", null, null, null, 1, List.of(new SourceChunk(chunk, "供水监测原文"))));
        var created = service.create(policy, enterprise, admin);
        store.putSource(source("摘要", "供水监测", null, null, null, 1, List.of(new SourceChunk(chunk, "供水监测更新原文"))));
        assertTrue(assertThrows(PolicyImpactException.class, () -> service.review(created.id(), 0, true, null, admin)).getMessage().contains("已变化"));
    }
    @Test void emptyChunksAreNotOriginalEvidence() {
        store.putSource(source("供水摘要", "供水监测", null, null, null, 1, List.of(new SourceChunk(UUID.randomUUID(), "  "))));
        assertEquals("SUMMARY_REFERENCE", service.create(policy, enterprise, admin).evidenceDetails().basis());
    }
    @Test void manufacturerIsNotRatedAsHighComplianceImpactForAnOperatorDuty() {
        var result = new DeterministicPolicyImpactAnalyzer().analyze(source("燃气监测", "燃气监测设备制造", "燃气运营企业", null, null, 1,
                List.of(new SourceChunk(UUID.randomUUID(), "燃气运营企业必须监测风险"))));
        assertEquals("INDIRECT_BUSINESS_CLUE", result.evidenceDetails().assessment().kind());
        assertEquals("MEDIUM", result.impactLevel());
    }
}
