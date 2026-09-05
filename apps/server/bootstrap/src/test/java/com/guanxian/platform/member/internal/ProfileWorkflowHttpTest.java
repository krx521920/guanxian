package com.guanxian.platform.member.internal;

import com.guanxian.platform.iam.SecurityConfig;
import com.guanxian.platform.member.web.*;
import com.guanxian.platform.shared.error.GlobalExceptionHandler;
import com.guanxian.platform.shared.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.*;
import static com.guanxian.platform.member.internal.ProfileWorkflowServiceTest.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=ProfileWorkflowHttpTest.App.class,properties={"guanxian.security.mode=demo","spring.flyway.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProfileWorkflowHttpTest {
    @Configuration @EnableAutoConfiguration
    @Import({SecurityConfig.class,ProfileWorkflowController.class,PublicEnterpriseController.class,ProfileReviewQueueController.class,GlobalExceptionHandler.class})
    static class App {
        @Bean ProfileWorkflowServiceTest fixture() throws Exception {var f=new ProfileWorkflowServiceTest();f.setup();return f;}
        @Bean DataSource dataSource(ProfileWorkflowServiceTest f){return f.jdbc.getDataSource();}
        @Bean PlatformTransactionManager transactionManager(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ProfileWorkflowService profiles(ProfileWorkflowServiceTest f){return f.service;}
        @Bean ActorScopeResolver scopes(){return auth->{
            var roles=auth.getAuthorities().stream().map(Object::toString).filter(s->s.startsWith("ROLE_")).map(s->s.substring(5)).collect(java.util.stream.Collectors.toSet());
            return new ActorScope(UUID.randomUUID(),auth.getName(),auth.getName(),ASSOCIATION,
                    roles.contains("ENTERPRISE_ADMIN")||roles.contains("ENTERPRISE_MEMBER")?ENTERPRISE:null,roles,Set.of());
        };}
    }
    @Autowired MockMvc mvc; @Autowired ProfileWorkflowServiceTest fixture;
    final String path="/api/v1/enterprise-profiles/"+ENTERPRISE;
    @Test void anonymousHasOnlyWhitelistedPublishedGetEndpoints() throws Exception {
        mvc.perform(get("/api/v1/public/enterprises")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/public/enterprises/"+ENTERPRISE)).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/enterprise-profile-reviews")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/public/enterprises").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        fixture.published();
        mvc.perform(get("/api/v1/public/enterprises/"+ENTERPRISE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.introduction").value("已审核简介"))
                .andExpect(jsonPath("$.data.contactPhone").doesNotExist()).andExpect(jsonPath("$.data.address").doesNotExist())
                .andExpect(jsonPath("$.data.associationId").doesNotExist()).andExpect(jsonPath("$.data.reviewNote").doesNotExist());
    }
    @Test void methodsEnforceRolesStrongVersionsValidationAndNoDirectPublish() throws Exception {
        String body=fixture.json.writeValueAsString(Map.of("content",fixture.content("HTTP草稿"),"baseVersion",0));
        mvc.perform(get(path).with(httpBasic("enterprise-member","member123"))).andExpect(status().isForbidden());
        mvc.perform(put(path+"/draft").with(httpBasic("enterprise-admin","enterprise123")).contentType("application/json").content(body)).andExpect(status().isPreconditionRequired());
        mvc.perform(put(path+"/draft").with(httpBasic("enterprise-admin","enterprise123")).header("If-Match","W/\"0\"").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(put(path+"/draft").with(httpBasic("enterprise-admin","enterprise123")).header("If-Match","\"0\"").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""))
                .andExpect(jsonPath("$.data.official.introduction").value("原简介")).andExpect(jsonPath("$.data.draft.status").value("DRAFT"));
        mvc.perform(post(path+"/submit").with(httpBasic("enterprise-admin","enterprise123")).header("If-Match","\"1\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.draft.status").value("SUBMITTED"));
        mvc.perform(post(path+"/review").with(httpBasic("association-operator","operator123")).header("If-Match","\"2\"").contentType("application/json").content("{\"approve\":true,\"note\":\"越权\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path+"/review").with(httpBasic("association-admin","admin123")).header("If-Match","\"2\"").contentType("application/json").content("{\"approve\":false,\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path+"/review").with(httpBasic("association-admin","admin123")).header("If-Match","\"2\"").contentType("application/json").content("{\"approve\":true,\"note\":\"核验通过\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.published").value(false));
        mvc.perform(post(path+"/consent").with(httpBasic("enterprise-admin","enterprise123")).header("If-Match","\"3\"").contentType("application/json").content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path+"/publish").with(httpBasic("enterprise-admin","enterprise123")).header("If-Match","\"3\""))
                .andExpect(status().isForbidden());
    }
    @Test void reviewerFeedbackCanBeReadOnlyWithinAuthorizedDraftScope() throws Exception {
        var s=fixture.submitted();fixture.run(()->fixture.service.review(ENTERPRISE,s.version(),false,"请补充证明材料",fixture.reviewer));
        mvc.perform(get(path).with(httpBasic("enterprise-admin","enterprise123"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.reviewNote").value("请补充证明材料"));
        mvc.perform(get(path).with(httpBasic("observer","observer123"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/public/enterprises")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
}
