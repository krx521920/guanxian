package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "guanxian.security.demo-users-enabled=false")
@AutoConfigureMockMvc
class DemoUsersDisabledIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    void disablingDemoUsersFailsClosedForAllEmbeddedCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(httpBasic("system-admin", "system123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
