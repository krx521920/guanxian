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
