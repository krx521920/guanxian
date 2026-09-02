package com.guanxian.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.rag.EmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.List;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=memory",
        "guanxian.member.seed-demo-data=false",
        "guanxian.ai.rag.external-model-data-egress-enabled=true",
        "guanxian.security.mode=demo"
})
@AutoConfigureMockMvc
@Import(PostgresKnowledgeIsolationIntegrationTest.TestEmbeddingConfiguration.class)
class PostgresKnowledgeIsolationIntegrationTest {
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
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void postgresPrivateDocumentIsHiddenFromOrdinarySameAssociationMember() throws Exception {
        var createResult = mockMvc.perform(post("/api/v1/knowledge/documents/text")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "PostgreSQL私有风险研判",
                                  "visibility": "PRIVATE",
                                  "status": "PUBLISHED",
                                  "content": "白桦编号测试管段只允许资料创建者和协会工作人员检索。"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        UUID documentId = UUID.fromString(objectMapper.readTree(
                createResult.getResponse().getContentAsString()).path("data").path("documentId").asText());

        mockMvc.perform(post("/api/v1/knowledge/documents/{documentId}/submit", documentId)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(org.springframework.http.HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(org.springframework.http.HttpHeaders.ETAG, "\"1\""));
        mockMvc.perform(post("/api/v1/knowledge/documents/{documentId}/review", documentId)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(org.springframework.http.HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(org.springframework.http.HttpHeaders.ETAG, "\"2\""));

        mockMvc.perform(get("/api/v1/knowledge/documents")
                        .with(httpBasic("association-admin", "admin123"))
                        .queryParam("includeDeleted", "false")
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(documentId.toString()))
                .andExpect(jsonPath("$.data.items[0].title").value("PostgreSQL私有风险研判"));

        mockMvc.perform(post("/api/v1/knowledge/questions")
                        .with(httpBasic("enterprise-member", "member123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"白桦编号测试管段允许谁检索？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.citations").isEmpty());

        mockMvc.perform(post("/api/v1/knowledge/questions")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"白桦编号测试管段允许谁检索？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("RETRIEVAL_SUMMARY"))
                .andExpect(jsonPath("$.data.retrievalMode").value("HYBRID_VECTOR"))
                .andExpect(jsonPath("$.data.citations.length()").value(greaterThan(0)));

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk
                WHERE embedding_status = 'READY'
                  AND vector_dimension = 8
                  AND jsonb_array_length(embedding) = 8
                  AND embedding_provider = 'test-deterministic'
                  AND embedding_model = 'test-8d'
                """, Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'KNOWLEDGE_CREATE'
                  AND resource_type = 'KNOWLEDGE_DOCUMENT'
                  AND resource_version = 1
                  AND outcome = 'SUCCESS'
                  AND actor_user_id IS NULL
                  AND actor_subject = 'association-admin'
                  AND actor_username = 'association-admin'
                  AND request_id IS NOT NULL
                """, Integer.class));
    }

    @Test
    void updatingUnknownPostgresDocumentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents/text")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentId": "%s",
                                  "title": "不存在的知识文档",
                                  "visibility": "ASSOCIATION",
                                  "status": "PUBLISHED",
                                  "content": "本次请求不应创建指定编号的新文档。"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KNOWLEDGE_REQUEST"));
    }

    @TestConfiguration
    static class TestEmbeddingConfiguration {
        @Bean
        @Primary
        EmbeddingProvider deterministicEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override public String providerName() { return "test-deterministic"; }
                @Override public String modelName() { return "test-8d"; }
                @Override public int dimensions() { return 8; }
                @Override public boolean enabled() { return true; }
                @Override public List<double[]> embed(List<String> inputs) {
                    return inputs.stream().map(TestEmbeddingConfiguration::vector).toList();
                }
            };
        }

        private static double[] vector(String input) {
            double[] vector = new double[8];
            for (int index = 0; index < input.length(); index++) {
                vector[Math.floorMod(input.codePointAt(index), vector.length)] += 1.0;
            }
            if (input.isEmpty()) vector[0] = 1.0;
            return vector;
        }
    }
}
