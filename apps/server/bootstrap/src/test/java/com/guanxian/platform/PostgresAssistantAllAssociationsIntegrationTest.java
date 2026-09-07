package com.guanxian.platform;

import com.guanxian.platform.ai.rag.ChatModelProvider;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService;
import com.guanxian.platform.ai.rag.KnowledgeIngestionService.KnowledgeTextDocument;
import com.guanxian.platform.ai.rag.KnowledgeRepository;
import com.guanxian.platform.ai.rag.PolicyRagService;
import com.guanxian.platform.ai.rag.PolicyRagService.RagQuestion;
import com.guanxian.platform.ai.rag.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true", "guanxian.business.repository=postgres",
        "guanxian.member.repository=memory", "guanxian.member.seed-demo-data=false",
        "guanxian.ai.rag.external-model-data-egress-enabled=false", "guanxian.security.mode=demo"
})
class PostgresAssistantAllAssociationsIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian").withUsername("guanxian").withPassword("test-only-password");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired KnowledgeRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void globalPublishedRetrievalAndAuditWorkWithoutWeakeningScopedOrPrivateReads() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        jdbc.update("INSERT INTO association(id, name) VALUES (?, ?), (?, ?)", first, "测试甲协会", second, "测试乙协会");
        var properties = new RagProperties();
        var ingestion = new KnowledgeIngestionService(repository, properties);
        ingest(ingestion, first, "甲协会已发布", "ASSOCIATION", "PUBLISHED");
        ingest(ingestion, second, "乙协会私有已发布", "PRIVATE", "PUBLISHED");
        ingest(ingestion, second, "乙协会待审核", "PUBLIC", "DRAFT");
        UUID deletedId = ingest(ingestion, second, "乙协会已删除", "PUBLIC", "PUBLISHED");
        var owner = new KnowledgeIngestionService.KnowledgeActor(second, null, "owner", "owner", true, "test-soft-delete");
        var beforeDelete = ingestion.getDocument(deletedId, owner, false);
        var deleted = ingestion.changeLifecycle(deletedId, beforeDelete.lifecycleVersion(),
                KnowledgeIngestionService.LifecycleAction.DELETE, false, null, owner);
        assertThat(deleted.deleted()).isTrue();

        // A deterministic in-process provider tests the real persistence path without model egress.
        ChatModelProvider provider = new ChatModelProvider() {
            public String providerName() { return "test-in-process"; }
            public boolean enabled() { return true; }
            public ChatResult complete(ChatRequest request) {
                return new ChatResult("甲乙协会均需保留巡检记录。[1][2]", "test-only", 20, 12, BigDecimal.ZERO, "global-model-test", 0);
            }
        };
        properties.setExternalModelDataEgressEnabled(true);
        var rag = new PolicyRagService(repository, provider, properties);
        var global = rag.ask(new RagQuestion(null, "system", "跨域巡检核验", 12, "global-read", true, true, true));
        assertThat(global.citations()).extracting(PolicyRagService.Citation::documentName)
                .containsExactlyInAnyOrder("甲协会已发布", "乙协会私有已发布");
        assertThat(global.mode()).isEqualTo("EXTERNAL_MODEL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval_trace WHERE id = ? AND association_id IS NULL", Integer.class, global.traceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM model_execution WHERE request_id = 'global-model-test' AND association_id IS NULL", Integer.class)).isEqualTo(1);

        var scoped = rag.ask(new RagQuestion(first, "system", "跨域巡检核验", 12, "scoped-read", true, false));
        assertThat(scoped.citations()).extracting(PolicyRagService.Citation::documentName).containsExactly("甲协会已发布");
        assertThat(rag.ask(new RagQuestion(second, "ordinary", "跨域巡检核验", 12, "ordinary-read", false, false)).citations()).isEmpty();
        assertThatThrownBy(() -> rag.ask(new RagQuestion(null, "ordinary", "跨域巡检核验", 12, "invalid", false, false, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UUID ingest(KnowledgeIngestionService ingestion, UUID association, String title, String visibility, String status) {
        return ingestion.ingest(new KnowledgeTextDocument(null, association, title, "POLICY", "MANUAL", null,
                visibility, status, "owner", "跨域巡检核验要求保留巡检记录。")).documentId();
    }
}
