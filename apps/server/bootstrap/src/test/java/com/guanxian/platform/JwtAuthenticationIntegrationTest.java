package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "guanxian.security.mode=jwt",
        "guanxian.security.jwt.issuer-uri=https://identity.example.com/realms/guanxian",
        "guanxian.security.jwt.jwk-set-uri=https://identity.example.com/realms/guanxian/certs"
})
@AutoConfigureMockMvc
class JwtAuthenticationIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void httpBasicCredentialsAreRejectedInJwtMode() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(httpBasic("system-admin", "system123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void verifiedJwtIdentityCanReachProtectedApi() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt()
                        .jwt(token -> token
                                .subject("oidc-subject")
                                .claim("preferred_username", "oidc-user")
                                .claim("name", "OIDC 用户")
                                .claim("organization", "北京地下管线协会"))
                        .authorities(
                                new SimpleGrantedAuthority("ROLE_ASSOCIATION_ADMIN"),
                                new SimpleGrantedAuthority("MEMBER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("oidc-subject"))
                .andExpect(jsonPath("$.data.username").value("oidc-user"))
                .andExpect(jsonPath("$.data.displayName").value("OIDC 用户"))
                .andExpect(jsonPath("$.data.roles[0]").value("ASSOCIATION_ADMIN"))
                .andExpect(jsonPath("$.data.permissions[0]").value("MEMBER_READ"));
    }
}
