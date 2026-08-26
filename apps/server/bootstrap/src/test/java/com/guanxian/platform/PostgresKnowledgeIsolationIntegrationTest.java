package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void postgresPrivateDocumentIsHiddenFromOrdinarySameAssociationMember() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents/text")
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
                .andExpect(status().isOk());

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
                .andExpect(jsonPath("$.data.citations.length()").value(greaterThan(0)));
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
}
