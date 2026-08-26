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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=memory",
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

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

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
                .andExpect(jsonPath("$.data.analysisMethod").value("DETERMINISTIC_LEXICAL"))
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
}
