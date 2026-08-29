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
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0",
        "guanxian.member.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=demo"
})
@AutoConfigureMockMvc
class PostgresMemberMigrationIntegrationTest {
    private static final String LEGACY_MEMBER_ID = "20000000-0000-0000-0000-000000000001";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password")
            .withInitScript("legacy-member-schema.sql");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("guanxian.security.demo.association-id",
                () -> "10000000-0000-0000-0000-000000000001");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void baselinesExistingSchemaMigratesColumnsAndPreservesMemberData() throws Exception {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(13, migrationCount);
        org.junit.jupiter.api.Assertions.assertEquals("member_import_batch", jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.member_import_batch')::text", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'user_account' AND column_name = 'external_subject'", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals("business_entity_history", jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.business_entity_history')::text", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("knowledge_chunk", jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.knowledge_chunk')::text", String.class));
        org.junit.jupiter.api.Assertions.assertEquals("outbox_event", jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.outbox_event')::text", String.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'policy_impact_model_execution_fk'
                  AND conrelid = 'policy_impact_analysis'::regclass
                """, Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'knowledge_chunk'
                  AND indexname = 'knowledge_chunk_search_idx'
                  AND indexdef LIKE '%USING gin (search_vector)%'
                """, Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'object_file'
                  AND constraint_type = 'UNIQUE'
                """, Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'notification_subscription' AND column_name = 'version'
                """, Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'notification_subscription_user_type_uq'
                """, Integer.class));

        mockMvc.perform(get("/api/v1/members/{id}", LEGACY_MEMBER_ID)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.data.name").value("迁移前存量会员企业"))
                .andExpect(jsonPath("$.data.category").value("存量企业"))
                .andExpect(jsonPath("$.data.introduction").value("迁移前已存在的数据"))
                .andExpect(jsonPath("$.data.capabilities").isEmpty())
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    void migratedSchemaCreatesAndDeletesMemberWhileRetainingAuditHistory() throws Exception {
        String body = """
                {
                  "name": "迁移后新增企业",
                  "unifiedSocialCreditCode": "91110000MIGRATION02",
                  "category": "技术服务单位"
                }
                """;
        String response = mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("system-admin", "system123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).path("data").path("id").asText();

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enterprise WHERE id = ?::uuid AND status = 'DELETED' AND deleted_at IS NOT NULL",
                Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE enterprise_id = ?::uuid AND action IN ('MEMBER_CREATE', 'MEMBER_DELETE')
                """, Integer.class, id));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints AS constraint_info
                JOIN information_schema.constraint_column_usage AS column_info
                  ON constraint_info.constraint_name = column_info.constraint_name
                 AND constraint_info.constraint_schema = column_info.constraint_schema
                WHERE constraint_info.table_schema = 'public'
                  AND constraint_info.table_name = 'audit_log'
                  AND constraint_info.constraint_type = 'FOREIGN KEY'
                  AND column_info.column_name IN ('association_id', 'enterprise_id')
                """, Integer.class));

        mockMvc.perform(put("/api/v1/members/{id}/restore", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enterprise WHERE id = ?::uuid AND deleted_at IS NULL",
                Integer.class, id));
    }
}
