package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.jdbc.Sql;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "guanxian.security.mode=jwt",
        "guanxian.security.jwt.issuer-uri=https://identity.example.com/realms/guanxian",
        "guanxian.security.jwt.jwk-set-uri=https://identity.example.com/realms/guanxian/certs",
        "guanxian.security.jwt.bootstrap-system-admin-subjects="
                + "oidc-subject,inactive-system-admin-subject,revoked-system-admin-subject"
})
@AutoConfigureMockMvc
@Sql(statements = """
        CREATE TABLE IF NOT EXISTS user_account (
          id UUID PRIMARY KEY,
          external_subject VARCHAR(200),
          status VARCHAR(32)
        );
        CREATE TABLE IF NOT EXISTS revoked_identity_subject (
          external_subject VARCHAR(200) PRIMARY KEY
        );
        """)
class JwtAuthenticationIntegrationTest {
    private static final String[] SYSTEM_ADMIN_READ_ENDPOINTS = {
            "/api/v1/access-bindings",
            "/api/v1/system-context/associations",
            "/api/v1/system-context/enterprises?associationId=00000000-0000-0000-0000-000000000100"
    };

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

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
                                new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"),
                                new SimpleGrantedAuthority("MEMBER_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("oidc-subject"))
                .andExpect(jsonPath("$.data.username").value("oidc-user"))
                .andExpect(jsonPath("$.data.displayName").value("OIDC 用户"))
                .andExpect(jsonPath("$.data.roles[0]").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.data.permissions[0]").value("MEMBER_READ"));
    }

    @Test
    void unboundSystemAdminOutsideBootstrapAllowlistIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(jwt()
                        .jwt(token -> token
                                .subject("untrusted-system-admin-subject")
                                .claim("preferred_username", "untrusted-system-admin"))
                        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_NOT_BOUND"));
    }

    @Test
    void inactiveSystemAccountCannotUseSystemAdminReadEndpointsWithAnOldToken() throws Exception {
        String subject = "inactive-system-admin-subject";
        jdbc.update("DELETE FROM user_account WHERE external_subject = ?", subject);
        jdbc.update("""
                INSERT INTO user_account(id, external_subject, status)
                VALUES (?, ?, 'INACTIVE')
                """, java.util.UUID.randomUUID(), subject);

        assertSystemAdminReadEndpointsReject(subject);
    }

    @Test
    void revokedSystemAdminSubjectCannotUseSystemAdminReadEndpointsWithAnOldToken() throws Exception {
        String subject = "revoked-system-admin-subject";
        jdbc.update("DELETE FROM revoked_identity_subject WHERE external_subject = ?", subject);
        jdbc.update("INSERT INTO revoked_identity_subject(external_subject) VALUES (?)", subject);

        assertSystemAdminReadEndpointsReject(subject);
    }

    private void assertSystemAdminReadEndpointsReject(String subject) throws Exception {
        for (String endpoint : SYSTEM_ADMIN_READ_ENDPOINTS) {
            mockMvc.perform(get(endpoint).with(jwt()
                            .jwt(token -> token
                                    .subject(subject)
                                    .claim("preferred_username", "stale-system-admin"))
                            .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("IDENTITY_NOT_BOUND"));
        }
    }
}
