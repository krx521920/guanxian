package com.guanxian.platform;

import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIf("com.guanxian.platform.IsolatedPolicyPostgres#available")
@SpringBootTest(properties={"spring.flyway.enabled=true","guanxian.business.repository=postgres",
        "guanxian.member.repository=memory","guanxian.member.seed-demo-data=false","guanxian.security.mode=demo"})
@AutoConfigureMockMvc
class PolicyCandidatesPostgresTest {
    static final IsolatedPolicyPostgres POSTGRES = new IsolatedPolicyPostgres();
    @AfterAll static void stopPostgres() { POSTGRES.close(); }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
        POSTGRES.register(r);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ActorScopeResolver scopes;
    UUID association, other, enterprise, anotherEnterprise, policy;
    @BeforeEach void seed() {
        association = UUID.randomUUID(); other = UUID.randomUUID();
        enterprise = UUID.randomUUID(); anotherEnterprise = UUID.randomUUID(); policy = UUID.randomUUID();
        jdbc.update("INSERT INTO association(id,name) VALUES (?,?),(?,?)", association, "候选测试-"+association, other, "外部-"+other);
        addEnterprise(enterprise, association, "甲测试企业", "燃气监测", "ACTIVE");
        addEnterprise(anotherEnterprise, association, "乙测试企业", "燃气", "ACTIVE");
        addEnterprise(UUID.randomUUID(), association, "待审核测试企业", "燃气监测", "PENDING_REVIEW");
        addEnterprise(UUID.randomUUID(), other, "外协会企业", "燃气监测", "ACTIVE");
        jdbc.update("INSERT INTO policy_document(id,association_id,title,summary,status,visibility,tags) VALUES (?,?,'候选测试政策','燃气监测','PUBLISHED','MEMBERS','[\"燃气监测\"]')", policy, association);
        identity("system-admin", "SYSTEM_ADMIN", null, null);
        identity("association-admin", "ASSOCIATION_ADMIN", association, null);
    }
    void addEnterprise(UUID id, UUID tenant, String name, String intro, String status) {
        jdbc.update("INSERT INTO enterprise(id,association_id,name,description,status,visibility) VALUES (?,?,?,?,?,'PRIVATE')", id, tenant, name, intro, status);
    }
    void identity(String login, String role, UUID tenant, UUID company) {
        doReturn(new ActorScope(null,login,login,tenant,company,Set.of(role),Set.of()))
                .when(scopes).resolve(argThat(a -> a != null && a.getName().equals(login)));
    }
    String url() { return "/api/v1/policies/"+policy+"/enterprise-candidates"; }

    @Test void readOnlyCandidatesWorkWithoutKnowledgeChunksAndAreScopedRankedAndPaged() throws Exception {
        long analyses = jdbc.queryForObject("SELECT count(*) FROM policy_impact_analysis", Long.class);
        long history = jdbc.queryForObject("SELECT count(*) FROM business_entity_history", Long.class);
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        mvc.perform(get(url()).with(user("test-observer").roles("OBSERVER"))).andExpect(status().isForbidden());
        mvc.perform(get(url()).param("size","1").with(httpBasic("association-admin","admin123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.examinedCount").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].enterpriseId").value(enterprise.toString()))
                .andExpect(jsonPath("$.data.items[0].matchedTopics").value(2))
                .andExpect(jsonPath("$.data.items[0].evidence[0].policyField").value("政策摘要"))
                .andExpect(jsonPath("$.data.method").value("PROFILE_TOPIC_CANDIDATE_V1"));
        mvc.perform(get(url()).param("size","1").param("page","1").with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.items[0].enterpriseId").value(anotherEnterprise.toString()));
        mvc.perform(get(url()).with(httpBasic("system-admin","system123")))
                .andExpect(jsonPath("$.data.associationId").value(association.toString()))
                .andExpect(jsonPath("$.data.total").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_impact_analysis", Long.class)).isEqualTo(analyses);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_entity_history", Long.class)).isEqualTo(history);
    }

    @Test void rejectsInvalidQueriesAndDoesNotTreatSearchAsSql() throws Exception {
        for (String value : new String[]{"0","101","-1"}) mvc.perform(get(url()).param("size",value).with(httpBasic("system-admin","system123"))).andExpect(status().isBadRequest());
        mvc.perform(get(url()).param("page","2147483647").with(httpBasic("system-admin","system123"))).andExpect(status().isBadRequest());
        mvc.perform(get(url()).param("q","x".repeat(101)).with(httpBasic("system-admin","system123"))).andExpect(status().isBadRequest());
        mvc.perform(get(url()).param("q","' OR 1=1 --").with(httpBasic("system-admin","system123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test void selectedTenantAndEnterpriseCannotBeBypassedWithQueryParameters() throws Exception {
        identity("system-admin", "SYSTEM_ADMIN", other, null);
        mvc.perform(get(url()).with(httpBasic("system-admin","system123"))).andExpect(status().isNotFound());
        identity("system-admin", "SYSTEM_ADMIN", association, enterprise);
        mvc.perform(get(url()).param("enterpriseId", anotherEnterprise.toString()).param("associationId", other.toString())
                        .with(httpBasic("system-admin","system123")))
                .andExpect(jsonPath("$.data.ownEnterpriseOnly").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].enterpriseId").value(enterprise.toString()));
        // Same authenticated principal, but authoritative scope resolves to an enterprise identity.
        identity("association-admin", "ENTERPRISE_ADMIN", association, enterprise);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.total").value(1));
        identity("association-admin", "ENTERPRISE_ADMIN", association, null);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123"))).andExpect(status().isForbidden());
    }

    @Test void visibleExternalPolicyDoesNotExposeExternalCompanies() throws Exception {
        jdbc.update("UPDATE policy_document SET association_id=?,visibility='PUBLIC' WHERE id=?",other,policy);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.associationId").value(association.toString()))
                .andExpect(jsonPath("$.data.total").value(2));
        jdbc.update("UPDATE policy_document SET visibility='PRIVATE' WHERE id=?",policy);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123"))).andExpect(status().isNotFound());
    }

    @Test void reloadUsesLiveProfilesAndExcludesRemovedOrInactiveRecords() throws Exception {
        jdbc.update("UPDATE enterprise SET description='食品经营',version=version+1 WHERE id=?",enterprise);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123"))).andExpect(jsonPath("$.data.total").value(1));
        jdbc.update("UPDATE enterprise SET deleted_at=now(),deleted_by_subject='test',status_before_delete=status,status='DELETED' WHERE id=?",anotherEnterprise);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123"))).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("UPDATE policy_document SET status='DRAFT' WHERE id=?",policy);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123"))).andExpect(status().isPreconditionFailed());
        jdbc.update("UPDATE policy_document SET status='PUBLISHED' WHERE id=?",policy);
        jdbc.update("UPDATE association SET status='DISABLED' WHERE id=?",association);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123"))).andExpect(status().isNotFound());
    }

    @Test void importedMetadataIsUsedOnlyWhileLivePolicyStillMatchesSnapshot() throws Exception {
        String run = "test-"+UUID.randomUUID();
        jdbc.update("INSERT INTO platform_dataset_import(id,association_id,source_sha256,source_filename,actor_subject,report) VALUES (?,?,?,'fixture','test','{}')",run,association,"a".repeat(64));
        jdbc.update("""
                INSERT INTO platform_source_record(id,import_id,association_id,kind,source_id,title,policy_id,payload)
                VALUES (gen_random_uuid(),?,?,'POLICY','POL-TEST','候选测试政策',?,
                  '{"title":"候选测试政策","summary":"燃气监测","source":{"涉及领域":"燃气监测","适用对象":"燃气运营单位","适用地区":"北京市"}}')
                """,run,association,policy);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.policyMetadata.audience").value("燃气运营单位"));
        jdbc.update("UPDATE policy_document SET summary='供水',tags='[]',version=version+1 WHERE id=?",policy);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.policyMetadata.audience").doesNotExist())
                .andExpect(jsonPath("$.data.policyVersion").value(1))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test void oversizedScopeIsExplicitlyTruncatedAndSearchCanNarrowIt() throws Exception {
        jdbc.update("INSERT INTO enterprise(association_id,name,description,status) SELECT ?, '范围测试-'||i, '燃气', 'ACTIVE' FROM generate_series(1,2001) i",association);
        mvc.perform(get(url()).with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.eligibleEnterpriseCount").value(2003))
                .andExpect(jsonPath("$.data.examinedCount").value(2000))
                .andExpect(jsonPath("$.data.truncated").value(true));
        mvc.perform(get(url()).param("q","甲测试").with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.examinedCount").value(1))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test void automaticOverviewComputesPairsWithoutCreatingRecordsAndProtectsScopes() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM policy_impact_analysis",Long.class);
        String uniqueTitle = "自动总览-"+policy;
        jdbc.update("UPDATE policy_document SET title=? WHERE id=?",uniqueTitle,policy);
        String overview = "/api/v1/policy-enterprise-candidates?q="+uniqueTitle;
        mvc.perform(get(overview)).andExpect(status().isUnauthorized());
        mvc.perform(get(overview).with(user("observer").roles("OBSERVER"))).andExpect(status().isForbidden());
        mvc.perform(get(overview).with(httpBasic("association-admin","admin123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].policyId").value(policy.toString()))
                .andExpect(jsonPath("$.data.items[0].candidateCount").value(2))
                .andExpect(jsonPath("$.data.items[0].examples[0].enterpriseId").value(enterprise.toString()))
                .andExpect(jsonPath("$.data.examinedEnterpriseCount").value(2));
        // Public external policies can be matched against our companies, never their private members.
        jdbc.update("UPDATE policy_document SET association_id=?,visibility='PUBLIC' WHERE id=?",other,policy);
        mvc.perform(get(overview).with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.items[0].associationId").value(association.toString()))
                .andExpect(jsonPath("$.data.items[0].candidateCount").value(2));
        identity("association-admin","ENTERPRISE_ADMIN",association,enterprise);
        mvc.perform(get(overview).param("associationId",other.toString()).param("enterpriseId",anotherEnterprise.toString())
                        .with(httpBasic("association-admin","admin123")))
                .andExpect(jsonPath("$.data.items[0].candidateCount").value(1))
                .andExpect(jsonPath("$.data.items[0].examples[0].enterpriseId").value(enterprise.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policy_impact_analysis",Long.class)).isEqualTo(before);
        mvc.perform(get(overview).param("size","21").with(httpBasic("association-admin","admin123"))).andExpect(status().isBadRequest());
    }
}
