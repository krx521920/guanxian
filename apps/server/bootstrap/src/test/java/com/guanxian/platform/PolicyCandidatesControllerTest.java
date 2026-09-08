package com.guanxian.platform;

import com.guanxian.platform.bootstrap.PolicyCandidatesController;
import com.guanxian.platform.policy.PolicyCandidateMatcher.Enterprise;
import com.guanxian.platform.policy.PolicyService;
import com.guanxian.platform.policy.PolicyView;
import com.guanxian.platform.shared.error.ApiException;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Fast scope/parameter tests. SQL execution and row filtering are covered by the PostgreSQL suite. */
class PolicyCandidatesControllerTest {
    final UUID association = UUID.randomUUID(), policyAssociation = UUID.randomUUID(), policyId = UUID.randomUUID(), enterpriseId = UUID.randomUUID();
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ActorScopeResolver scopes = mock(ActorScopeResolver.class);
    final PolicyService policies = mock(PolicyService.class);
    final TestingAuthenticationToken auth = new TestingAuthenticationToken("test", "unused");
    final PolicyCandidatesController controller = new PolicyCandidatesController(jdbc, scopes, policies);
    final List<String> sql = new ArrayList<>();
    final List<SqlParameterSource> parameters = new ArrayList<>();

    @BeforeEach void stub() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            String query = call.getArgument(0); sql.add(query); parameters.add(call.getArgument(1));
            if (query.contains("SELECT name FROM association")) return List.of("测试范围");
            if (query.contains("FROM platform_source_record")) return List.of();
            return List.of(new Enterprise(enterpriseId,"测试企业",null,"燃气监测","[]","[]","[]",3));
        });
        when(policies.get(eq(policyId), any(), eq(false))).thenReturn(new PolicyView(policyId.toString(), "测试政策",null,null,null,null,
                null,null,null,"PUBLISHED","燃气监测",List.of(),policyAssociation,"PUBLIC",4,false,false,Instant.EPOCH));
    }
    void scope(String role, UUID tenant, UUID company) {
        when(scopes.resolve(auth)).thenReturn(new ActorScope(null,"test","test",tenant,company,Set.of(role),Set.of()));
    }
    @Test void invalidQueryNeverTouchesIdentityOrData() {
        assertThatThrownBy(() -> controller.page(policyId,"",-1,20,auth)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.page(policyId,"",0,101,auth)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.page(policyId,"x".repeat(101),0,20,auth)).isInstanceOf(ApiException.class);
        verifyNoInteractions(scopes, policies);
        assertThat(sql).isEmpty();
    }
    @Test void incompleteEnterpriseIdentityFailsClosedBeforeLoadingPolicy() {
        scope("ENTERPRISE_ADMIN", association, null);
        assertThatThrownBy(() -> controller.page(policyId,"",0,20,auth)).isInstanceOf(ForbiddenException.class);
        verify(policies, never()).get(any(), any(), anyBoolean());
        assertThat(sql).isEmpty();
    }
    @Test void associationScopeForPublicExternalPolicyUsesActorsCompanies() {
        scope("ASSOCIATION_ADMIN", association, null);
        var result = controller.page(policyId,"甲'",0,20,auth);
        assertThat(result).isNotNull();
        assertThat(parameters).allSatisfy(p -> assertThat(p.getValue("association")).isEqualTo(association));
        assertThat(parameters).allSatisfy(p -> assertThat(p.getValue("q")).isEqualTo("甲'"));
        assertThat(sql.getLast()).doesNotContain("甲'").contains("position(lower(:q)","e.status='ACTIVE'","e.deleted_at IS NULL");
    }
    @Test void globalAdministratorDefaultsToPolicyAssociationAndDoesNotReadEveryTenant() {
        scope("SYSTEM_ADMIN", null, null);
        controller.page(policyId,"",0,20,auth);
        assertThat(parameters).allSatisfy(p -> assertThat(p.getValue("association")).isEqualTo(policyAssociation));
        assertThat(sql.getLast()).contains("e.association_id=:association");
    }
    @Test void selectedEnterpriseAndBoundEnterpriseBothConstrainTheRead() {
        for (String role : List.of("SYSTEM_ADMIN", "ENTERPRISE_MEMBER")) {
            scope(role, association, enterpriseId);
            controller.page(policyId,"",0,20,auth);
            assertThat(sql.getLast()).contains("e.id=:enterprise");
            assertThat(parameters.getLast().getValue("enterprise")).isEqualTo(enterpriseId);
            assertThat(parameters.getLast().getValue("limit")).isEqualTo(2000);
        }
    }

    @Test void overviewUsesOneSharedEnterpriseReadForTwentyPoliciesAndSelectedScope() throws Exception {
        scope("SYSTEM_ADMIN",association,enterpriseId);
        var policy = new PolicyView(policyId.toString(),"供水政策",null,null,null,null,null,null,null,
                "PUBLISHED","燃气监测",List.of(),policyAssociation,"PUBLIC",4,false,false,Instant.EPOCH);
        when(policies.page(any(),eq(""),eq(false),eq(0),eq(20))).thenReturn(
                new com.guanxian.platform.policy.PolicyPage(java.util.Collections.nCopies(20,policy),20,0,20));
        var row = mock(java.sql.ResultSet.class);
        when(row.getObject("association_id",UUID.class)).thenReturn(association);
        when(row.getObject("id",UUID.class)).thenReturn(enterpriseId);
        when(row.getString("name")).thenReturn("本企业");
        when(row.getString("description")).thenReturn("燃气监测");
        when(jdbc.query(anyString(),any(SqlParameterSource.class),org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            String query = call.getArgument(0); sql.add(query); parameters.add(call.getArgument(1));
            if (query.startsWith("SELECT id FROM association")) return List.of(association,policyAssociation);
            RowMapper<?> mapper = call.getArgument(2);
            return List.of(mapper.mapRow(row,0));
        });
        var result = controller.overview("",0,20,auth).data();
        assertThat(result.items()).hasSize(20).allSatisfy(item -> {
            assertThat(item.associationId()).isEqualTo(association);
            assertThat(item.candidateCount()).isEqualTo(1);
        });
        assertThat(sql.stream().filter(s -> s.contains("FROM enterprise e")).count()).isEqualTo(1);
        assertThat(parameters.getLast().getValue("allowed")).isEqualTo(List.of(association));
        assertThat(sql.getLast()).contains("e.id=:enterprise");
        verify(policies,times(1)).page(any(),eq(""),eq(false),eq(0),eq(20));
        verify(policies,never()).get(any(),any(),anyBoolean());
        verify(jdbc,never()).update(anyString(),any(SqlParameterSource.class));
    }

    @Test void overviewRejectsMissingScopeAndOversizedPages() {
        assertThatThrownBy(() -> controller.overview("",0,21,auth)).isInstanceOf(ApiException.class);
        scope("ENTERPRISE_ADMIN",association,null);
        assertThatThrownBy(() -> controller.overview("",0,10,auth)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(policies);
    }
}
