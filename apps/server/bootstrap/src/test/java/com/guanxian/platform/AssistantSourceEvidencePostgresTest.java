package com.guanxian.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.assistant.*;
import com.guanxian.platform.bootstrap.AssistantSourceEvidenceTools;
import com.guanxian.platform.bootstrap.AssistantSourceEvidenceTools.SearchQuery;
import com.guanxian.platform.bootstrap.AssistantSourceEvidenceTools.Period;
import com.guanxian.platform.bootstrap.SourceDirectoryService;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.flyway.enabled=true", "guanxian.business.repository=postgres",
        "guanxian.member.repository=memory", "guanxian.member.seed-demo-data=false", "guanxian.security.mode=demo",
        "guanxian.ai.provider.enabled=false", "guanxian.ai.rag.external-model-data-egress-enabled=false"})
@AutoConfigureMockMvc
class AssistantSourceEvidencePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian").withUsername("guanxian").withPassword("test-only-password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AssistantSourceEvidenceTools tools;
    @Autowired PlatformAssistantService assistant;
    @Autowired MockMvc mvc;
    @MockitoSpyBean ActorScopeResolver scopes;
    UUID first, second;
    String marker;
    @BeforeEach void setup() {
        first = UUID.randomUUID(); second = UUID.randomUUID(); marker = "证据测试" + UUID.randomUUID();
        for (UUID association : List.of(first, second)) {
            jdbc.update("INSERT INTO association(id,name) VALUES (?,?)", association, "隔离协会" + association);
            jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,?,?,'fixture','test','{}')",
                    association.toString(), association, "a".repeat(64));
        }
        doReturn(actor(first, "ASSOCIATION_ADMIN")).when(scopes).resolve(argThat(a -> a != null && a.getName().equals("association-admin")));
    }
    private ActorScope actor(UUID association, String role) { return new ActorScope(null, "test", "test", association,
            role.startsWith("ENTERPRISE") ? UUID.randomUUID() : null, Set.of(role), Set.of()); }
    private ToolContext context(UUID association, String role) { return new ToolContext(Map.of(AssistantAccessContext.TOOL_CONTEXT_KEY,
            new AssistantAccessContext(actor(association, role), Set.of("MEMBER_READ", "POLICY_READ")), AssistantBusinessResults.CONTEXT_KEY, new AssistantBusinessResults())); }
    private SearchQuery query(Period period) { return new SearchQuery(marker, period, null, null, 0); }
    private UUID seed(UUID association, String kind, String published, String state) throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> fields = new LinkedHashMap<>(Map.of("记录状态", state, "记录状态代码", "CANDIDATE_NOTICE",
                "证据摘要", "管线监测公开线索", "关联企业及角色", marker + "：候选单位，不等于中标",
                "金额及口径", "项目总额100万元；未披露本企业份额", "private", "SECRET_PHONE_13800000000"));
        if (published != null) fields.put("发布日期", published);
        String payload = json.writeValueAsString(Map.of("source", Map.of("采购内容", "保留原始资料", "联系人", "SECRET_CONTACT"),
                "privateRelations", List.of("SECRET_RELATION"), "publicEvidence", Map.of("fields", fields,
                "record", Map.of("id", "EV-" + id, "title", marker + state, "checkedOn", "2026-09-08", "sourceUrl", "https://example.test/notice/" + id),
                "supportingUrls", List.of("https://example.test/correction", "javascript:alert(1)", "https://user:password@example.test/secret"))));
        jdbc.update("INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload) VALUES (?,?,?,?,?,?,?::jsonb)",
                id, association.toString(), association, kind, "SRC-" + id, "保留原始标题", payload);
        return id;
    }
    @Test void scopedToolAndHttpUseSameAllowlistWithoutMutatingBusinessData() throws Exception {
        UUID own = seed(first, "TENDER", "2026-09-08", "候选公示");
        UUID foreign = seed(second, "TENDER", "2026-09-08", "其他协会中标结果");
        seed(first, "ACTIVITY", "2026-09-08", "已报道活动");
        String before = jdbc.queryForObject("SELECT payload::text FROM platform_source_record WHERE id=?", String.class, own);
        var result = tools.searchTenders(query(Period.ALL), context(first, "ASSOCIATION_ADMIN"));
        assertThat(result.status()).isEqualTo("OK"); assertThat(result.total()).isEqualTo(1);
        assertThat(result.items().getFirst().id()).isEqualTo(own);
        assertThat(result.toString()).contains("候选公示", "未披露本企业份额", "并非", "EV-")
                .doesNotContain("SECRET_", "javascript:", "user:password", "其他协会中标结果");
        assertThat(result.items().getFirst().source().supportingUrls()).containsExactly("https://example.test/correction");
        assertThat(tools.searchActivities(query(Period.ALL), context(first, "ENTERPRISE_MEMBER")).total()).isEqualTo(1);
        assertThat(tools.searchTenders(query(Period.ALL), context(null, "SYSTEM_ADMIN")).total()).isEqualTo(2);
        assertThat(tools.readEvidence("TENDER", foreign, context(first, "ASSOCIATION_ADMIN")).status()).isEqualTo("UNAVAILABLE");
        assertThat(tools.searchTenders(query(Period.ALL), context(first, "OBSERVER")).status()).isEqualTo("FORBIDDEN");
        mvc.perform(get("/api/v1/source-directory").param("kind", "TENDER").param("recordId", own.toString())
                .with(httpBasic("association-admin", "admin123"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/source-directory").param("kind", "TENDER").param("recordId", foreign.toString())
                .with(httpBasic("association-admin", "admin123"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/source-directory?kind=TENDER&recordId=" + own)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT payload::text FROM platform_source_record WHERE id=?", String.class, own)).isEqualTo(before);
        jdbc.update("UPDATE association SET status='DISABLED' WHERE id=?", second);
        assertThat(tools.searchTenders(query(Period.ALL), context(null, "SYSTEM_ADMIN")).total()).isEqualTo(1);
    }
    @Test void validPublicationDatesAreFilteredInclusivelyWithoutGuessingMissingOrMalformedDates() throws Exception {
        for (String date : Arrays.asList("2025-09-08", "2026-09-08", "2025-09-07", "2026-09-09", "2026-02-30", "2026-13-01", "待核实", null))
            seed(first, "TENDER", date, "历史结果");
        var query = new SearchQuery(marker, Period.CUSTOM, "2025-09-08", "2026-09-08", 0);
        var result = tools.searchTenders(query, context(first, "ASSOCIATION_ADMIN"));
        assertThat(result.status()).isEqualTo("OK"); assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).extracting(i -> i.fields().get("发布日期")).containsExactly("2026-09-08", "2025-09-08");
        assertThat(tools.searchTenders(new SearchQuery(marker, Period.CUSTOM, "2026-02-30", null, 0), context(first, "ASSOCIATION_ADMIN")).status()).isEqualTo("INVALID");
        assertThat(tools.searchTenders(new SearchQuery("' OR 1=1 --", Period.ALL, null, null, 0), context(first, "ASSOCIATION_ADMIN")).total()).isZero();
        mvc.perform(get("/api/v1/source-directory").param("kind", "TENDER").param("fromDate", "2026-09-08").param("toDate", "2025-09-08")
                .with(httpBasic("association-admin", "admin123"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/source-directory").param("kind", "TENDER").param("fromDate", "2026-02-30")
                .with(httpBasic("association-admin", "admin123"))).andExpect(status().isBadRequest());
    }
    @Test void paginationRetainsTotalAndReadByIdRechecksCurrentAvailability() throws Exception {
        for (int i = 0; i < 7; i++) seed(first, "TENDER", "2026-09-08", "历史公告" + i);
        var firstPage = tools.searchTenders(query(Period.ALL), context(first, "ASSOCIATION_ADMIN"));
        var secondPage = tools.searchTenders(new SearchQuery(marker, Period.ALL, null, null, 1), context(first, "ASSOCIATION_ADMIN"));
        assertThat(firstPage.total()).isEqualTo(7); assertThat(firstPage.items()).hasSize(5); assertThat(secondPage.items()).hasSize(2);
        assertThat(firstPage.items()).extracting(AssistantBusinessResults.Item::id).doesNotContainAnyElementsOf(secondPage.items().stream().map(AssistantBusinessResults.Item::id).toList());
        UUID id = firstPage.items().getFirst().id();
        assertThat(tools.readEvidence("TENDER", id, context(first, "ASSOCIATION_ADMIN")).items()).hasSize(1);
        jdbc.update("DELETE FROM platform_source_record WHERE id=?", id);
        assertThat(tools.readEvidence("TENDER", id, context(first, "ASSOCIATION_ADMIN")).status()).isEqualTo("UNAVAILABLE");
        assertThat(tools.readEvidence("ENTERPRISE", id, context(first, "ASSOCIATION_ADMIN")).status()).isEqualTo("INVALID");
        assertThat(tools.readEvidence("TENDER", null, context(first, "ASSOCIATION_ADMIN")).status()).isEqualTo("INVALID");
    }
    @Test void modelOffStreamsRealSourceReceiptsAndDoesNotRouteTenderQuestionToMemberList() throws Exception {
        seed(first, "TENDER", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "候选公示");
        seed(first, "ACTIVITY", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), "活动报道");
        var access = new AssistantAccessContext(actor(first, "ASSOCIATION_ADMIN"), Set.of("MEMBER_READ", "POLICY_READ"));
        var question = new PlatformAssistantService.AssistantQuestion(access, UUID.randomUUID(), "查询近一年“" + marker + "”会员企业招标和活动资料", 5, "协会工作台", "/", "source-test");
        var events = assistant.stream(question).collectList().block();
        assertThat(events).isNotEmpty();
        var answer = events.getLast().answer();
        assertThat(answer.mode()).isEqualTo("LOCAL_BUSINESS_QUERY"); assertThat(answer.modelConnected()).isFalse();
        assertThat(answer.businessResults()).extracting(AssistantBusinessResults.Result::kind).containsExactly("TENDER_EVIDENCE", "ACTIVITY_EVIDENCE");
        assertThat(answer.businessResults()).allSatisfy(r -> assertThat(r.total()).isEqualTo(1));
        assertThat(answer.answer()).contains("未调用大模型", "来源", "快照").doesNotContain("SECRET_");
        assertThat(events.stream().filter(e -> e.businessResults() != null).flatMap(e -> e.businessResults().stream()).toList()).isNotEmpty();
    }
}
