package com.guanxian.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIf("com.guanxian.platform.IsolatedPolicyPostgres#available")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=demo"
})
@AutoConfigureMockMvc
class PostgresPolicyImpactIntegrationTest {
    private static final UUID ASSOCIATION = UUID.fromString("00000000-0000-0000-0000-000000000106");
    private static final UUID ENTERPRISE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID POLICY = UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_VERSION = UUID.fromString("52000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_ASSOCIATION = UUID.fromString("52000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_ENTERPRISE = UUID.fromString("52000000-0000-0000-0000-000000000102");
    private static final UUID OTHER_POLICY = UUID.fromString("52000000-0000-0000-0000-000000000103");
    private static final UUID OTHER_ANALYSIS = UUID.fromString("52000000-0000-0000-0000-000000000104");

    static final IsolatedPolicyPostgres POSTGRES = new IsolatedPolicyPostgres();
    @AfterAll static void stopPostgres() { POSTGRES.close(); }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        POSTGRES.register(registry);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore analysisStore;

    @Test
    void originalSourceMustBeUnambiguousAndDemandIsNotACapability() {
        UUID company = UUID.randomUUID(), policy = UUID.randomUUID();
        String title = "来源关联测试-" + policy;
        String source = "https://example.test/policy/" + policy;
        jdbc.update("""
                INSERT INTO enterprise(id,association_id,name,description,cooperation_needs,status)
                VALUES (?,?,'来源测试企业','食品服务','["燃气监测合作需求"]','ACTIVE')
                """, company, ASSOCIATION);
        jdbc.update("""
                INSERT INTO policy_document(id,association_id,title,source_url,summary,status,visibility)
                VALUES (?,?,?,?,'燃气监测摘要','PUBLISHED','MEMBERS')
                """, policy, ASSOCIATION, title, source);
        addOriginalDocument(title, source + "/different-source", "燃气监测旧来源正文");
        var summary = analysisStore.loadSource(policy, company).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(summary.chunks()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(summary.enterpriseProfile()).doesNotContain("燃气", "监测合作需求");
        UUID validChunk = addOriginalDocument(title, source, "燃气监测本次正文");
        org.assertj.core.api.Assertions.assertThat(analysisStore.loadSource(policy, company).orElseThrow().chunks())
                .extracting(com.guanxian.platform.ai.impact.PolicyImpactAnalysisStore.SourceChunk::id).containsExactly(validChunk);
        addOriginalDocument(title, source, "同名同来源但无法唯一确认的正文");
        org.assertj.core.api.Assertions.assertThat(analysisStore.loadSource(policy, company).orElseThrow().chunks()).isEmpty();
    }

    private UUID addOriginalDocument(String title, String url, String text) {
        UUID document = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_document(id,association_id,title,document_type,source_type,source_url,
                    visibility,status,current_version,created_by_subject)
                VALUES (?, ?, ?, 'POLICY', 'URL', ?, 'ASSOCIATION', 'PUBLISHED', 1, 'test')
                """, document, ASSOCIATION, title, url);
        jdbc.update("""
                INSERT INTO knowledge_document_version(id,document_id,version,parser_name,parser_version,status,created_by_subject)
                VALUES (?, ?, 1, 'test', '1', 'READY', 'test')
                """, version, document);
        jdbc.update("""
                INSERT INTO knowledge_chunk(id,document_version_id,chunk_index,content,content_hash,token_count)
                VALUES (?, ?, 0, ?, repeat('c',64), 20)
                """, chunk, version, text);
        return chunk;
    }

    @Test
    void persistsDeterministicEvidenceReviewAuditHistoryAndEnterpriseIsolation() throws Exception {
        insertSourceData();

        String response = mockMvc.perform(post("/api/v1/policy-impact-analyses")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyDocumentId":"%s","enterpriseId":"%s"}
                                """.formatted(POLICY, ENTERPRISE)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.impactLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.analysisMethod").value("DETERMINISTIC_TOPIC_V2"))
                .andExpect(jsonPath("$.data.modelExecutionId").doesNotExist())
                .andExpect(jsonPath("$.data.evidenceChunkIds.length()").value(greaterThan(0)))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}", id)
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.enterpriseId").value(ENTERPRISE.toString()));

        mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/reanalyze", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"9\""))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/reanalyze", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/review", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"comment\":\"出处已核验\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewedBySubject").value("association-admin"));

        mockMvc.perform(get("/api/v1/policy-impact-analyses/page")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].enterpriseId").value(ENTERPRISE.toString()));

        mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}/history", id)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].action").value("APPROVE"));

        org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE resource_type = 'POLICY_IMPACT_ANALYSIS' AND resource_id = ?
                """, Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM business_entity_history
                 WHERE resource_type = 'POLICY_IMPACT_ANALYSIS' AND resource_id = ?::uuid
                """, Integer.class, id));

        jdbc.update("UPDATE enterprise SET status='DISABLED', updated_at=now() WHERE id=?", ENTERPRISE);
        mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}", id)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
        mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}", id)
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/policy-impact-analyses/page")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(post("/api/v1/policy-impact-analyses")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyDocumentId":"%s","enterpriseId":"%s"}
                                """.formatted(POLICY, ENTERPRISE)))
                .andExpect(status().isPreconditionFailed());

        jdbc.update("UPDATE enterprise SET status='ACTIVE', updated_at=now() WHERE id=?", ENTERPRISE);
        mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}", id)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk());

        insertCrossAssociationAnalysis();
        mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}", OTHER_ANALYSIS)
                        .with(httpBasic("system-admin", "system123")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/policy-impact-analyses/page")
                        .with(httpBasic("system-admin", "system123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
        mockMvc.perform(post("/api/v1/policy-impact-analyses")
                        .with(httpBasic("system-admin", "system123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyDocumentId":"%s","enterpriseId":"%s"}
                                """.formatted(OTHER_POLICY, OTHER_ENTERPRISE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_IMPACT_SCOPE_VIOLATION"));
        mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/reanalyze", OTHER_ANALYSIS)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/review", OTHER_ANALYSIS)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void importedSummaryIsTraceableButDatabaseAndApiBothRejectApproval() throws Exception {
        UUID enterprise = UUID.randomUUID(), policy = UUID.randomUUID();
        String run = "summary-test-"+UUID.randomUUID();
        jdbc.update("INSERT INTO enterprise(id,association_id,name,description,status) VALUES (?,?,'摘要测试企业','供水监测','ACTIVE')",enterprise,ASSOCIATION);
        jdbc.update("INSERT INTO policy_document(id,association_id,title,summary,status,visibility,tags) VALUES (?,?,'摘要测试政策','供水企业应当监测','PUBLISHED','MEMBERS','[\"供水监测\"]')",policy,ASSOCIATION);
        jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,?,?,'fixture','test','{}')",run,ASSOCIATION,"b".repeat(64));
        jdbc.update("""
                INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,policy_id,payload)
                VALUES (gen_random_uuid(),?,?,'POLICY','SUMMARY-TEST','摘要测试政策',?,
                '{"title":"摘要测试政策","summary":"供水企业应当监测","source":{"涉及领域":"供水监测","适用对象":"供水运营企业","适用地区":"北京市","当前状态":"现行"}}')
                """,run,ASSOCIATION,policy);
        try {
            String response = mockMvc.perform(post("/api/v1/policy-impact-analyses")
                            .with(httpBasic("association-admin","admin123")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"policyDocumentId\":\""+policy+"\",\"enterpriseId\":\""+enterprise+"\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.evidenceChunkIds.length()").value(0))
                    .andExpect(jsonPath("$.data.evidenceDetails.basis").value("SUMMARY_REFERENCE"))
                    .andExpect(jsonPath("$.data.evidenceDetails.references[0].quote").value("供水企业应当监测"))
                    .andExpect(jsonPath("$.data.evidenceDetails.assessment.checks[0].explanation").value(org.hamcrest.Matchers.containsString("供水运营企业")))
                    .andReturn().getResponse().getContentAsString();
            UUID id = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
            mockMvc.perform(get("/api/v1/policy-impact-analyses/{id}",id).with(httpBasic("association-admin","admin123")))
                    .andExpect(jsonPath("$.data.evidenceDetails.basis").value("SUMMARY_REFERENCE"));
            mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/review",id).with(httpBasic("association-admin","admin123"))
                            .header(HttpHeaders.IF_MATCH,"\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                    .andExpect(status().isPreconditionFailed());
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE policy_impact_analysis SET status='APPROVED' WHERE id=?",id));
            jdbc.update("UPDATE policy_document SET summary='供水监测更新',version=version+1 WHERE id=?",policy);
            mockMvc.perform(put("/api/v1/policy-impact-analyses/{id}/reanalyze",id).with(httpBasic("association-admin","admin123"))
                            .header(HttpHeaders.IF_MATCH,"\"0\""))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.evidenceDetails.policyVersion").value(1))
                    .andExpect(jsonPath("$.data.evidenceDetails.assessment.checks[0].explanation").value(org.hamcrest.Matchers.containsString("缺少适用对象")));
        } finally {
            jdbc.update("DELETE FROM policy_impact_analysis WHERE policy_document_id=?",policy);
            jdbc.update("DELETE FROM platform_source_record WHERE policy_id=?",policy);
            jdbc.update("DELETE FROM platform_dataset_import WHERE id=?",run);
            jdbc.update("DELETE FROM policy_document WHERE id=?",policy);
            jdbc.update("DELETE FROM enterprise WHERE id=?",enterprise);
        }
    }

    private void insertSourceData() {
        jdbc.update("""
                INSERT INTO enterprise (
                    id, association_id, name, category, description, capabilities, products,
                    cooperation_needs, status)
                VALUES (?, ?, '政策影响测试企业', '智慧管网', '从事燃气管线泄漏监测与数字孪生',
                        '["管线监测","泄漏预警"]'::jsonb,
                        '["燃气监测终端"]'::jsonb, '[]'::jsonb, 'ACTIVE')
                """, ENTERPRISE, ASSOCIATION);
        jdbc.update("""
                INSERT INTO policy_document (
                    id, association_id, title, source_url, status, summary, visibility)
                VALUES (?, ?, '燃气地下管线安全巡检办法', 'https://example.test/policy-impact',
                        'PUBLISHED', '明确燃气管线巡检和隐患整改要求', 'MEMBERS')
                """, POLICY, ASSOCIATION);
        jdbc.update("""
                INSERT INTO knowledge_document (
                    id, association_id, title, document_type, source_type, source_url,
                    visibility, status, current_version, created_by_subject)
                VALUES (?, ?, '燃气地下管线安全巡检办法', 'POLICY', 'URL',
                        'https://example.test/policy-impact', 'ASSOCIATION', 'PUBLISHED', 1, 'test')
                """, DOCUMENT, ASSOCIATION);
        jdbc.update("""
                INSERT INTO knowledge_document_version (
                    id, document_id, version, parser_name, parser_version, status, created_by_subject)
                VALUES (?, ?, 1, 'test', '1', 'READY', 'test')
                """, DOCUMENT_VERSION, DOCUMENT);
        jdbc.update("""
                INSERT INTO knowledge_chunk (
                    id, document_version_id, chunk_index, content, content_hash, token_count)
                VALUES (?, ?, 0,
                        '燃气地下管线运营企业必须建立泄漏监测和巡检记录，对安全隐患限期整改。',
                        repeat('a', 64), 30)
                """, UUID.fromString("52000000-0000-0000-0000-000000000004"), DOCUMENT_VERSION);
        jdbc.update("""
                INSERT INTO knowledge_chunk (
                    id, document_version_id, chunk_index, content, content_hash, token_count)
                VALUES (?, ?, 1,
                        '管线数据应当按照标准汇交，并制定风险应急处置方案。',
                        repeat('b', 64), 24)
                """, UUID.fromString("52000000-0000-0000-0000-000000000005"), DOCUMENT_VERSION);
    }

    private void insertCrossAssociationAnalysis() {
        jdbc.update("""
                INSERT INTO association (id, name, status)
                VALUES (?, '政策影响隔离测试协会', 'ACTIVE')
                """, OTHER_ASSOCIATION);
        jdbc.update("""
                INSERT INTO enterprise (id, association_id, name, category, status)
                VALUES (?, ?, '政策影响隔离企业', '测试单位', 'ACTIVE')
                """, OTHER_ENTERPRISE, OTHER_ASSOCIATION);
        jdbc.update("""
                INSERT INTO policy_document (
                    id, association_id, title, source_url, status, summary, visibility)
                VALUES (?, ?, '外协会政策', 'https://example.test/foreign-policy-impact',
                        'PUBLISHED', '外协会隔离验证', 'MEMBERS')
                """, OTHER_POLICY, OTHER_ASSOCIATION);
        jdbc.update("""
                INSERT INTO policy_impact_analysis (
                    id, policy_document_id, enterprise_id, impact_level, summary,
                    evidence_chunk_ids, status, version)
                VALUES (?, ?, ?, 'MEDIUM', '外协会政策影响结果', '[]'::jsonb, 'PENDING_REVIEW', 0)
                """, OTHER_ANALYSIS, OTHER_POLICY, OTHER_ENTERPRISE);
    }
}
