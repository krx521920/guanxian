package com.guanxian.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres",
        "guanxian.notification.repository=postgres",
        "guanxian.member.seed-demo-data=false",
        "guanxian.security.mode=jwt",
        "guanxian.security.jwt.issuer-uri=https://identity.example.test/realms/guanxian",
        "guanxian.security.jwt.jwk-set-uri=https://identity.example.test/realms/guanxian/certs",
        "guanxian.security.jwt.health-check-enabled=false",
        "guanxian.security.jwt.bootstrap-system-admin-subjects=dependency-integration-admin",
        "guanxian.storage.backend=minio",
        "guanxian.storage.bucket=guanxian-phase5",
        "guanxian.storage.rate-limit.enabled=true",
        "guanxian.storage.rate-limit-per-minute=2"
})
@AutoConfigureMockMvc
class PostgresMinioRedisIntegrationTest {
    private static final UUID ASSOCIATION =
            UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID ENTERPRISE =
            UUID.fromString("52000000-0000-0000-0000-000000000001");
    private static final String MINIO_ACCESS_KEY = "phase5minio";
    private static final String MINIO_SECRET_KEY = "phase5-minio-secret-key";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian")
            .withUsername("guanxian")
            .withPassword("test-only-password");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("guanxian.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("guanxian.storage.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("guanxian.storage.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("guanxian.storage.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void createScope() {
        jdbc.update("INSERT INTO association(id, name, status) VALUES (?, ?, 'ACTIVE')",
                ASSOCIATION, "依赖集成测试协会");
        jdbc.update("""
                INSERT INTO enterprise(id, association_id, name, category, status)
                VALUES (?, ?, ?, '测试单位', 'ACTIVE')
                """, ENTERPRISE, ASSOCIATION, "依赖集成测试企业");
    }

    @Test
    void attachmentRoundTripUsesPostgresMinioAndRedisAndHealthReflectsThem() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));

        String firstResponse = upload("dependency-1.txt", "stored in minio")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bucketName").value("guanxian-phase5"))
                .andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(objectMapper.readTree(firstResponse).path("data").path("id").asText());

        mockMvc.perform(get("/api/v1/attachments/{id}/content", firstId)
                        .with(systemAdministrator())
                        .header("X-Guanxian-Association-Id", ASSOCIATION)
                        .header("X-Guanxian-Enterprise-Id", ENTERPRISE))
                .andExpect(status().isOk())
                .andExpect(content().bytes("stored in minio".getBytes(StandardCharsets.UTF_8)));

        upload("dependency-2.txt", "second object")
                .andExpect(status().isCreated());
        upload("dependency-3.txt", "must be rejected")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM object_file", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM rate_limit_audit
                WHERE route_key = 'attachment:upload'
                  AND decision IN ('ALLOWED', 'REJECTED')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action = 'FILE_UPLOAD' AND outcome = 'SUCCESS'
                """, Integer.class)).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions upload(String filename, String value)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, MediaType.TEXT_PLAIN_VALUE,
                value.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/v1/attachments")
                .file(file)
                .param("associationId", ASSOCIATION.toString())
                .param("enterpriseId", ENTERPRISE.toString())
                .with(systemAdministrator())
                .header("X-Guanxian-Association-Id", ASSOCIATION)
                .header("X-Guanxian-Enterprise-Id", ENTERPRISE));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            systemAdministrator() {
        return jwt()
                .jwt(token -> token
                        .subject("dependency-integration-admin")
                        .claim("preferred_username", "dependency-integration-admin"))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"),
                        new SimpleGrantedAuthority("ENTERPRISE_WRITE"),
                        new SimpleGrantedAuthority("MEMBER_READ"));
    }
}
