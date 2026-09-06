package com.guanxian.platform.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.member.web.MyEnterpriseController;
import com.guanxian.platform.shared.error.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static com.guanxian.platform.iam.EnterpriseInvitationServiceTest.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Bearer requests exercise the actual converter, database binding resolver and method security. */
@SpringBootTest(classes=EnterpriseInvitationHttpTest.App.class, properties={
        "guanxian.security.mode=jwt", "guanxian.security.jwt.bootstrap-system-admin-subjects=reviewer",
        "guanxian.security.jwt.issuer-uri=https://fixture.invalid/realm", "guanxian.security.jwt.jwk-set-uri=https://fixture.invalid/certs",
        "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
class EnterpriseInvitationHttpTest {
    @Configuration @EnableAutoConfiguration
    @Import({SecurityConfig.class,EnterpriseOwnerAuthorities.class,EnterpriseInvitationService.class,
            EnterpriseInvitationController.class,EnterpriseOnboardingController.class,DatabaseActorScopeResolver.class,
            EnterpriseTeamController.class,EnterpriseTeamService.class,
            ManagedEnterpriseAccountController.class,ManagedEnterpriseAccounts.class,ManagedEnterpriseAuthorities.class,
            CurrentUserController.class,MyEnterpriseController.class,GlobalExceptionHandler.class})
    static class App {
        @Bean DataSource dataSource() throws Exception {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:invitation-http-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            var jdbc=new JdbcTemplate(ds); schema(jdbc); seed(jdbc);
            jdbc.execute("ALTER TABLE enterprise ADD version BIGINT DEFAULT 0 NOT NULL");
            jdbc.execute("CREATE TABLE association_relationship(source_association_id UUID,target_association_id UUID,status VARCHAR(32),allow_member_data BOOLEAN,suspended_at TIMESTAMP,revoked_at TIMESTAMP,expires_at TIMESTAMP)");
            return ds;
        }
        @Bean ManagedEnterpriseAccountsTest.FakeIdentityProvider accountProvider() { return new ManagedEnterpriseAccountsTest.FakeIdentityProvider(); }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EnterpriseInvitationService invitations;
    @Autowired ManagedEnterpriseAccountsTest.FakeIdentityProvider accountProvider;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean MemberService members;

    @Test void teamEndpointsEnforceRealBearerScopeVersionAndReadOnlyMemberGrant() throws Exception {
        var invitation=invitations.create(new EnterpriseInvitations.Create(ENTERPRISE,"owner.user"),admin());
        var claimed=invitations.claim(new EnterpriseInvitations.Claim(invitation.token(),true),owner());
        invitations.review(claimed.id(),claimed.version(),new EnterpriseInvitations.Review("APPROVE","真实 HTTP 测试前置核验"),admin());
        String base="/api/v1/my-enterprise/team";
        mvc.perform(get(base+"/members")).andExpect(status().isUnauthorized());
        mvc.perform(get(base+"/members").header("Authorization","Bearer admin")).andExpect(status().isForbidden());
        var issued=mvc.perform(post(base+"/invitations").header("Authorization","Bearer owner")
                        .header("X-Guanxian-Enterprise-Id",FOREIGN_ENTERPRISE).contentType("application/json")
                        .content("{\"username\":\"team.user\",\"role\":\"SYSTEM_ADMIN\",\"enterpriseId\":\""+FOREIGN_ENTERPRISE+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.invitation.enterpriseId").value(ENTERPRISE.toString()))
                .andExpect(jsonPath("$.data.invitation.targetRole").value("ENTERPRISE_MEMBER")).andReturn();
        var data=mapper.readTree(issued.getResponse().getContentAsString()).path("data");
        String token=data.path("token").asText(),id=data.path("invitation").path("id").asText();
        when(decoder.decode("team")).thenReturn(jwt("team-subject","team.user"));
        mvc.perform(post("/api/v1/onboarding/claim").header("Authorization","Bearer team").contentType("application/json")
                .content("{\"token\":\""+token+"\",\"confirmed\":true}")).andExpect(status().isOk());
        var approved=mvc.perform(put("/api/v1/enterprise-invitations/"+id+"/review").header("Authorization","Bearer admin")
                .header("X-Guanxian-Association-Id",ASSOCIATION).header("If-Match","\"1\"").contentType("application/json")
                .content("{\"decision\":\"APPROVE\",\"note\":\"核验普通成员权限\"}")).andExpect(status().isOk()).andReturn();
        String memberId=mapper.readTree(approved.getResponse().getContentAsString()).path("data").path("accountId").asText();
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer team")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[0]").value("ENTERPRISE_MEMBER"));
        mvc.perform(get(base+"/members").header("Authorization","Bearer team")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/my-enterprise").header("Authorization","Bearer team").contentType("application/json").content("{\"name\":\"越权\",\"category\":\"测试\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(base+"/members/"+memberId+"/disable").header("Authorization","Bearer owner").contentType("application/json").content("{\"note\":\"离职\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(base+"/members/"+memberId+"/disable").header("Authorization","Bearer owner").header("If-Match","\"9\"").contentType("application/json").content("{\"note\":\"离职\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(put(base+"/members/"+memberId+"/disable").header("Authorization","Bearer owner").header("If-Match","\"0\"").contentType("application/json").content("{\"note\":\"离职\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer team")).andExpect(status().isForbidden());
        mvc.perform(get(base+"/members").header("Authorization","Bearer owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("INACTIVE")).andExpect(jsonPath("$.data.items[0].externalSubject").doesNotExist());
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM enterprise_managed_account");
        accountProvider.username=null;accountProvider.operation=null;accountProvider.password=null;accountProvider.created=0;
        accountProvider.available=true;accountProvider.failPassword=false;accountProvider.failAfterCreate=false;
        jdbc.update("DELETE FROM enterprise_owner_grant"); jdbc.update("DELETE FROM enterprise_owner_invitation");
        jdbc.update("DELETE FROM user_account"); jdbc.update("DELETE FROM revoked_identity_subject"); jdbc.update("DELETE FROM audit_log");
        when(decoder.decode("owner")).thenReturn(jwt("owner-subject","owner.user"));
        when(decoder.decode("admin")).thenReturn(Jwt.withTokenValue("admin").header("alg","RS256").subject("reviewer").claim("preferred_username","reviewer").claim("roles",List.of("SYSTEM_ADMIN")).build());
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        when(members.get(eq(ENTERPRISE),any())).thenReturn(profile(0));
        when(members.update(eq(ENTERPRISE),eq(0L),any(),any())).thenReturn(profile(1));
    }
    private static MemberProfile profile(long version) {
        return new MemberProfile(ENTERPRISE,ASSOCIATION,"既有企业","TEST-CREDIT","技术服务","内部地址","负责人","内部号码","owner@example.test","简介",
                List.of(),List.of(),List.of(),List.of(),List.of(),"MEMBERS",version==0?"ACTIVE":"PENDING_REVIEW",version,Instant.now(),Instant.now(),null,null,null);
    }
    @Test void unknownAndAnonymousIdentitiesCannotReadBusinessOrCreateInvitations() throws Exception {
        for(String path:List.of("/api/v1/onboarding/session","/api/v1/my-enterprise","/api/v1/enterprise-invitations"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/onboarding/session").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/onboarding/session").header("Authorization","Bearer owner"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.username").value("owner.user")).andExpect(jsonPath("$.data.roles").doesNotExist());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer owner")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/my-enterprise").header("Authorization","Bearer owner")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/enterprise-invitations").header("Authorization","Bearer owner").contentType("application/json").content("{\"enterpriseId\":\""+ENTERPRISE+"\",\"username\":\"owner.user\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(members);
    }
    @Test void invitationConfirmationAndReviewUnlockTheExistingEnterpriseAndStopAfterDisable() throws Exception {
        var issued=mvc.perform(post("/api/v1/enterprise-invitations").header("Authorization","Bearer admin")
                        .header("X-Guanxian-Association-Id",ASSOCIATION).contentType("application/json")
                        .content("{\"enterpriseId\":\""+ENTERPRISE+"\",\"username\":\"owner.user\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var data=mapper.readTree(issued.getResponse().getContentAsString()).get("data");
        String token=data.get("token").asText(),id=data.get("invitation").get("id").asText();
        mvc.perform(post("/api/v1/onboarding/claim").header("Authorization","Bearer owner").contentType("application/json")
                        .content("{\"token\":\""+token+"\",\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLAIMED"));
        mvc.perform(get("/api/v1/my-enterprise").header("Authorization","Bearer owner")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/enterprise-invitations/"+id+"/review").header("Authorization","Bearer admin")
                        .header("X-Guanxian-Association-Id",ASSOCIATION).contentType("application/json").content("{\"decision\":\"APPROVE\",\"note\":\"电话核验负责人授权\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put("/api/v1/enterprise-invitations/"+id+"/review").header("Authorization","Bearer admin")
                        .header("X-Guanxian-Association-Id",ASSOCIATION).header("If-Match","\"1\"").contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"note\":\"电话核验负责人授权\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enterpriseId").value(ENTERPRISE.toString()))
                .andExpect(jsonPath("$.data.roles[0]").value("ENTERPRISE_ADMIN"));
        mvc.perform(get("/api/v1/my-enterprise").header("Authorization","Bearer owner")
                        .header("X-Guanxian-Enterprise-Id",FOREIGN_ENTERPRISE))
                .andExpect(status().isOk()).andExpect(header().string("ETag","\"0\""))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.profile.id").value(ENTERPRISE.toString()));
        // Injected path-like/unknown fields cannot select another update target.
        mvc.perform(put("/api/v1/my-enterprise").header("Authorization","Bearer owner").header("If-Match","\"0\"")
                        .contentType("application/json").content("{\"id\":\""+FOREIGN_ENTERPRISE+"\",\"name\":\"既有企业\",\"category\":\"技术服务\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PROFILE_DRAFT_REQUIRED"));
        verify(members,never()).update(any(),anyLong(),any(),any());
        jdbc.update("UPDATE user_account SET status='INACTIVE',version=version+1 WHERE external_subject='owner-subject'");
        mvc.perform(get("/api/v1/my-enterprise").header("Authorization","Bearer owner")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/onboarding/session").header("Authorization","Bearer owner")).andExpect(status().isForbidden());
    }
    @Test void directAccountHttpCreatesBoundOwnerRequiresConfirmationAndResetInvalidatesBearer() throws Exception {
        String path="/api/v1/enterprise-accounts/enterprises/"+ENTERPRISE;
        String body="{\"username\":\"http.owner\",\"note\":\"已核验负责人\",\"confirmed\":true,\"role\":\"SYSTEM_ADMIN\",\"enterpriseId\":\""+FOREIGN_ENTERPRISE+"\"}";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer owner").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer admin").header("X-Guanxian-Association-Id",ASSOCIATION).contentType("application/json").content(body))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post(path).header("Authorization","Bearer admin").header("X-Guanxian-Association-Id",FOREIGN_ASSOCIATION).header("If-Match","\"0\"").contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer admin").header("X-Guanxian-Association-Id",ASSOCIATION).header("If-Match","\"0\"").contentType("application/json").content(body.replace("\"confirmed\":true","\"confirmed\":false")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization","Bearer admin").header("X-Guanxian-Association-Id",ASSOCIATION).header("If-Match","\"0\"").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.account.enterpriseId").value(ENTERPRISE.toString())).andExpect(jsonPath("$.data.temporaryPassword").isNotEmpty());
        var jwt=Jwt.withTokenValue("managed").header("alg","RS256").subject(accountProvider.subject).issuedAt(java.time.Instant.now().minusSeconds(2))
                .claim("preferred_username","http.owner").build();
        when(decoder.decode("managed")).thenReturn(jwt);
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer managed"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("ENTERPRISE_ADMIN"))
                .andExpect(jsonPath("$.data.enterpriseId").value(ENTERPRISE.toString()));
        mvc.perform(post(path+"/reset-password").header("Authorization","Bearer managed").header("If-Match","\"2\"")
                .contentType("application/json").content("{\"note\":\"不允许企业自行使用管理重置\",\"confirmed\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path+"/reset-password").header("Authorization","Bearer admin").header("X-Guanxian-Association-Id",ASSOCIATION)
                .header("If-Match","\"2\"").contentType("application/json").content("{\"note\":\"核验后重置\",\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer managed")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer admin").header("X-Guanxian-Association-Id",ASSOCIATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.temporaryPassword").doesNotExist());
    }
    @Test void enterpriseMembersCanReadButCannotWriteAndSensitiveFieldsAreNotReturned() throws Exception {
        jdbc.update("INSERT INTO user_account(id,external_subject,username,association_id,enterprise_id,status) VALUES(?,'staff-subject','staff',?,?,'ACTIVE')",UUID.randomUUID(),ASSOCIATION,ENTERPRISE);
        when(decoder.decode("staff")).thenReturn(Jwt.withTokenValue("staff").header("alg","RS256").subject("staff-subject").claim("preferred_username","staff").claim("roles",List.of("ENTERPRISE_MEMBER")).build());
        mvc.perform(get("/api/v1/my-enterprise").header("Authorization","Bearer staff"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canEdit").value(false))
                .andExpect(jsonPath("$.data.profile.contactPhone").isEmpty()).andExpect(jsonPath("$.data.profile.contactEmail").isEmpty())
                .andExpect(jsonPath("$.data.profile.unifiedSocialCreditCode").isEmpty());
        mvc.perform(put("/api/v1/my-enterprise").header("Authorization","Bearer staff").header("If-Match","\"0\"").contentType("application/json").content("{\"name\":\"x\",\"category\":\"y\"}"))
                .andExpect(status().isForbidden());
        verify(members,never()).update(any(),anyLong(),any(),any());
    }
    @Test void mixedAdministratorRoleCannotUseDelegatedScopeAsMyEnterprise() throws Exception {
        when(decoder.decode("mixed")).thenReturn(Jwt.withTokenValue("mixed").header("alg","RS256").subject("reviewer").claim("preferred_username","reviewer")
                .claim("roles",List.of("SYSTEM_ADMIN","ENTERPRISE_ADMIN")).build());
        mvc.perform(get("/api/v1/my-enterprise").header("Authorization","Bearer mixed")
                        .header("X-Guanxian-Association-Id",ASSOCIATION).header("X-Guanxian-Enterprise-Id",ENTERPRISE))
                .andExpect(status().isForbidden());
        verifyNoInteractions(members);
    }
}
