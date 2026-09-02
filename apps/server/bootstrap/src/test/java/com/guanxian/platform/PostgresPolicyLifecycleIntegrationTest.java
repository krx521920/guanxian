package com.guanxian.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class PostgresPolicyLifecycleIntegrationTest {
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
    void persistsLifecycleOptimisticLockAuditHistoryAndRestore() throws Exception {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO policy_document (title, status, visibility)
                VALUES ('禁止写入的孤儿政策', 'DRAFT', 'PRIVATE')
                """));

        String createBody = """
                {
                  "title": "PostgreSQL政策纵切测试",
                  "authority": "北京地下管线协会",
                  "documentNumber": "测试〔2026〕1号",
                  "level": "行业协会",
                  "category": "信息管理",
                  "publishDate": "2026-08-20",
                  "effectiveDate": "2026-08-21",
                  "summary": "仅供自动化测试",
                  "tags": ["迁移", "审计"],
                  "visibility": "PUBLIC"
                }
                """;
        String response = mockMvc.perform(post("/api/v1/policies")
                        .with(httpBasic("system-admin", "system123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/policies/{id}/submit", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(put("/api/v1/policies/{id}/review", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"comment\":\"出处已核验\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(put("/api/v1/policies/{id}/disable", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(delete("/api/v1/policies/{id}", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.data.deleted").value(true));

        mockMvc.perform(put("/api/v1/policies/{id}/restore", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"3\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(get("/api/v1/policies/{id}/history", id)
                        .with(httpBasic("system-admin", "system123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].action").value("RESTORE"));

        org.junit.jupiter.api.Assertions.assertEquals(5, jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                 WHERE resource_type = 'POLICY_DOCUMENT' AND resource_id = ?
                """, Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(5, jdbc.queryForObject("""
                SELECT count(*) FROM business_entity_history
                 WHERE resource_type = 'POLICY_DOCUMENT' AND resource_id = ?::uuid
                """, Integer.class, id));
    }
}
