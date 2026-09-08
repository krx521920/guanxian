package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.Set;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.argThat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties={"spring.flyway.enabled=true","guanxian.business.repository=postgres",
        "guanxian.member.repository=memory","guanxian.member.seed-demo-data=false","guanxian.security.mode=demo"})
@AutoConfigureMockMvc
class SourceDirectoryPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian").withUsername("guanxian").withPassword("test-only-password");
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ActorScopeResolver scopes;

    @Test void annualActivitiesAndHistoricalEvidenceAreScopedSearchableAndAllowlisted() throws Exception {
        String run = "annual-test-" + java.util.UUID.randomUUID();
        String marker = "年检证据" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,'00000000-0000-0000-0000-000000000106',?,'fixture','test','{}')",
                run, "e".repeat(64));
        try {
            for (String kind : new String[]{"TENDER", "ACTIVITY"}) {
                jdbc.update("""
                    INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload)
                    VALUES(gen_random_uuid(),?,'00000000-0000-0000-0000-000000000106',?,'ANNUAL-TEST','保留原名',
                      jsonb_build_object('source',jsonb_build_object('采购内容','保留原始摘要'),
                        'publicEvidence',jsonb_build_object('fields',jsonb_build_object('记录状态','候选公示',
                          '记录状态代码','CANDIDATE_NOTICE','关联企业及角色',?::text,'证据摘要','原文已核实摘要',
                          'private','must-not-leak','发布日期','2026-09-08'),
                          'record',jsonb_build_object('id','EV-TEST','title','证据标题','checkedOn','2026-09-08',
                            'sourceUrl','https://example.test/notice','private','also-hidden'),
                          'supportingUrls',jsonb_build_array('https://example.test/outcome'))))
                    """, run, kind, marker + "：候选供应商，不等于中标");
                mvc.perform(get("/api/v1/source-directory").param("kind",kind).param("q",marker)
                                .with(httpBasic("association-admin","admin123")))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                        .andExpect(jsonPath("$.data.items[0].title").value("保留原名"))
                        .andExpect(jsonPath("$.data.items[0].evidence.title").value("证据标题"))
                        .andExpect(jsonPath("$.data.items[0].evidence.supportingUrls[0]").value("https://example.test/outcome"))
                        .andExpect(jsonPath("$.data.items[0].fields['记录状态代码']").value("CANDIDATE_NOTICE"))
                        .andExpect(jsonPath("$.data.items[0].fields.private").doesNotExist())
                        .andExpect(jsonPath("$.data.items[0].evidence.private").doesNotExist())
                        .andExpect(jsonPath("$.data.items[0].evidence.record").doesNotExist());
            }
            mvc.perform(get("/api/v1/source-directory?kind=ACTIVITY")).andExpect(status().isUnauthorized());
            doReturn(new ActorScope(null,"association-admin","association-admin",
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000888"),null,
                    Set.of("ASSOCIATION_ADMIN"),Set.of())).when(scopes)
                    .resolve(argThat(a -> a != null && a.getName().equals("association-admin")));
            mvc.perform(get("/api/v1/source-directory").param("kind","ACTIVITY").param("q",marker)
                            .with(httpBasic("association-admin","admin123")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        } finally {
            jdbc.update("DELETE FROM platform_source_record WHERE import_id=?",run);
            jdbc.update("DELETE FROM platform_dataset_import WHERE id=?",run);
        }
    }

    @Test void tenderLeadsSearchByBusinessTagsAndExposeDistinctAcquisitionAndSubmissionDates() throws Exception {
        String run = "tender-lead-test-" + java.util.UUID.randomUUID();
        String title = "公告检索测试-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,'00000000-0000-0000-0000-000000000106',?,'fixture','test','{}')",
                run, "d".repeat(64));
        try {
            for (String day : new String[]{"07", "08"}) {
                jdbc.update("""
                    INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload)
                    VALUES (gen_random_uuid(),?,'00000000-0000-0000-0000-000000000106','TENDER',?,?,
                      jsonb_build_object('source',jsonb_build_object('发布日期',?::text,'需求标签','独有非开挖测试标签',
                        '公告类型','资格预审','文件获取截止时间','2026-09-14 17:00:00','提交截止类型','资格预审申请截止',
                        '截止或开标时间','2026-09-20 09:00:00','业务关联说明','管线施工线索，资格未核实','内部结论','不可泄露')))
                    """, run, "TEST-" + day, title + day, "2026-09-" + day);
            }
            mvc.perform(get("/api/v1/source-directory").param("kind", "TENDER").param("q", "独有非开挖测试标签")
                            .with(httpBasic("association-admin", "admin123")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                    .andExpect(jsonPath("$.data.items[0].sourceId").value("TEST-08"))
                    .andExpect(jsonPath("$.data.items[0].fields['公告类型']").value("资格预审"))
                    .andExpect(jsonPath("$.data.items[0].fields['文件获取截止时间']").value("2026-09-14 17:00:00"))
                    .andExpect(jsonPath("$.data.items[0].fields['截止或开标时间']").value("2026-09-20 09:00:00"))
                    .andExpect(jsonPath("$.data.items[0].fields['内部结论']").doesNotExist());
        } finally {
            jdbc.update("DELETE FROM platform_source_record WHERE import_id=?", run);
            jdbc.update("DELETE FROM platform_dataset_import WHERE id=?", run);
        }
    }

    @Test void correctionsExposeReviewedDisplayValuesAndPreserveAllowlistedOriginalsOnly() throws Exception {
        String run = "source-correction-test-" + java.util.UUID.randomUUID();
        String title = "更正展示测试-" + java.util.UUID.randomUUID();
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,'00000000-0000-0000-0000-000000000106',?,'fixture','test','{}')",
                run, "c".repeat(64));
        jdbc.update("""
                INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload)
                VALUES (?,?,'00000000-0000-0000-0000-000000000106','POLICY','TEST-CORRECTION',?,
                  '{"source":{"实施日期":"2026-07-24","文号或标准号":"原编号","private":"hidden"},
                    "correction":{"id":"test-v1","checkedOn":"2026-09-08","reason":"实施日期无独立证据",
                      "fields":{"实施日期":"","文号或标准号":"更正编号","日期说明":"日期待核实","private":"must-not-leak"},
                      "private":"also-hidden","evidenceUrls":["https://example.test/evidence"]}}')
                """, id, run, title);
        try {
            mvc.perform(get("/api/v1/source-directory").param("kind", "POLICY").param("q", title)
                            .with(httpBasic("association-admin", "admin123")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].fields['实施日期']").value(""))
                    .andExpect(jsonPath("$.data.items[0].fields['文号或标准号']").value("更正编号"))
                    .andExpect(jsonPath("$.data.items[0].originalFields['实施日期']").value("2026-07-24"))
                    .andExpect(jsonPath("$.data.items[0].correction.reason").value("实施日期无独立证据"))
                    .andExpect(jsonPath("$.data.items[0].fields.private").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].originalFields.private").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].correction.private").doesNotExist());
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                    "SELECT payload->'source'->>'实施日期' FROM platform_source_record WHERE id=?", String.class, id))
                    .isEqualTo("2026-07-24");
        } finally {
            jdbc.update("DELETE FROM platform_source_record WHERE id=?", id);
            jdbc.update("DELETE FROM platform_dataset_import WHERE id=?", run);
        }
    }

    @Test void requiresAuthenticationAppliesTenantScopeAndReturnsOnlyDisplayFields() throws Exception {
        // Demo identities are tenant-bound by default. Explicitly exercise the
        // verified, unscoped system-admin scope used by production's resolver.
        doReturn(new ActorScope(null,"system-admin","system-admin",null,null,Set.of("SYSTEM_ADMIN"),Set.of()))
                .when(scopes).resolve(argThat(a -> a != null && a.getName().equals("system-admin")));
        jdbc.update("INSERT INTO association(id,name) VALUES ('00000000-0000-0000-0000-000000000777','隔离协会')");
        for (String suffix : new String[]{"106","777"}) {
            String id = "00000000-0000-0000-0000-000000000"+suffix;
            jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,?::uuid,?,'fixture.csv','test','{}')",
                    "test-"+suffix,id,"a".repeat(64));
            jdbc.update("INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,payload) VALUES (gen_random_uuid(),?,?::uuid,'TENDER','BID-1',?,?::jsonb)",
                    "test-"+suffix,id,"测试公告"+suffix,"{\"source\":{\"采购内容\":\"原文摘要\",\"建议匹配企业（ID）\":\"不应泄露的内部候选\",\"unexpected\":\"hidden\"}}");
        }
        mvc.perform(get("/api/v1/source-directory?kind=TENDER")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/source-directory?kind=TENDER").with(httpBasic("association-admin","admin123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("测试公告106"))
                .andExpect(jsonPath("$.data.items[0].fields['采购内容']").value("原文摘要"))
                .andExpect(jsonPath("$.data.items[0].fields.unexpected").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].fields['建议匹配企业（ID）']").doesNotExist());
        mvc.perform(get("/api/v1/source-directory?kind=TENDER").with(httpBasic("system-admin","system123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/source-directory").param("kind","TENDER").param("q","' OR 1=1 --").with(httpBasic("association-admin","admin123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/source-directory?kind=TENDER&size=0").with(httpBasic("system-admin","system123")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/source-directory?kind=UNKNOWN").with(httpBasic("system-admin","system123")))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE association SET status='DISABLED' WHERE id='00000000-0000-0000-0000-000000000777'");
        mvc.perform(get("/api/v1/source-directory?kind=TENDER").with(httpBasic("system-admin","system123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }
}
