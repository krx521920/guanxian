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
            CurrentUserController.class,MyEnterpriseController.class,GlobalExceptionHandler.class})
    static class App {
        @Bean DataSource dataSource() throws Exception {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:invitation-http-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
            var jdbc=new JdbcTemplate(ds); schema(jdbc); seed(jdbc);
            jdbc.execute("CREATE TABLE association_relationship(source_association_id UUID,target_association_id UUID,status VARCHAR(32),allow_member_data BOOLEAN,suspended_at TIMESTAMP,revoked_at TIMESTAMP,expires_at TIMESTAMP)");
            return ds;
        }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean MemberService members;
    @BeforeEach void setup() {
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
