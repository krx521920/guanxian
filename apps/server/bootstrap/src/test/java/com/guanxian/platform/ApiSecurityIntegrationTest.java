package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityIntegrationTest {
    private static final String UNKNOWN_MEMBER_ID = "00000000-0000-0000-0000-000000000001";
    private static final String VALID_MEMBER = """
            {
              "name": "权限边界测试企业",
              "unifiedSocialCreditCode": "91110000SECURITY0001",
              "category": "测试单位"
            }
            """;

    @Autowired
    MockMvc mockMvc;

    @Test
    void bothHealthEndpointsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("guanxian-server"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest(name = "valid credentials for {0}")
    @MethodSource("validAccounts")
    void everyDemoAccountCanAuthenticate(String username, String password, String expectedRole) throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(httpBasic(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0]").value(expectedRole))
                .andExpect(jsonPath("$.data.permissions").isArray());
    }

    @ParameterizedTest(name = "anonymous request is rejected: {0} {1}")
    @MethodSource("protectedRequests")
    void protectedEndpointsRejectAnonymousRequests(String method, String path) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "PUT" -> put(path).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "DELETE" -> delete(path);
            default -> get(path);
        };

        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void wrongPasswordIsRejectedWithUnifiedError() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(httpBasic("association-admin", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().encoding(StandardCharsets.UTF_8))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void readOnlyEnterpriseMemberCannotWriteMemberData() throws Exception {
        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("enterprise-member", "member123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(put("/api/v1/members/{id}", UNKNOWN_MEMBER_ID)
                        .with(httpBasic("enterprise-member", "member123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MEMBER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(delete("/api/v1/members/{id}", UNKNOWN_MEMBER_ID)
                        .with(httpBasic("enterprise-member", "member123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void enterpriseAdminCannotUseAssociationOnlyFunctions() throws Exception {
        mockMvc.perform(get("/api/v1/dashboards/association")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(delete("/api/v1/members/{id}", UNKNOWN_MEMBER_ID)
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void associationAdminCannotUseEnterpriseDashboard() throws Exception {
        mockMvc.perform(get("/api/v1/dashboards/enterprise")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void observerCannotUseMatchingOrCollaborationFunctions() throws Exception {
        mockMvc.perform(get("/api/v1/matches").with(httpBasic("observer", "observer123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/collaborations").with(httpBasic("observer", "observer123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private static Stream<Arguments> validAccounts() {
        return Stream.of(
                Arguments.of("system-admin", "system123", "SYSTEM_ADMIN"),
                Arguments.of("association-admin", "admin123", "ASSOCIATION_ADMIN"),
                Arguments.of("association-operator", "operator123", "ASSOCIATION_OPERATOR"),
                Arguments.of("enterprise-admin", "enterprise123", "ENTERPRISE_ADMIN"),
                Arguments.of("enterprise-member", "member123", "ENTERPRISE_MEMBER"),
                Arguments.of("observer", "observer123", "OBSERVER"));
    }

    private static Stream<Arguments> protectedRequests() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/users/me"),
                Arguments.of("GET", "/api/v1/members"),
                Arguments.of("POST", "/api/v1/members"),
                Arguments.of("PUT", "/api/v1/members/" + UNKNOWN_MEMBER_ID),
                Arguments.of("DELETE", "/api/v1/members/" + UNKNOWN_MEMBER_ID),
                Arguments.of("GET", "/api/v1/policies"),
                Arguments.of("GET", "/api/v1/matches"),
                Arguments.of("GET", "/api/v1/collaborations"),
                Arguments.of("GET", "/api/v1/dashboards/association"),
                Arguments.of("GET", "/api/v1/dashboards/enterprise"));
    }
}
