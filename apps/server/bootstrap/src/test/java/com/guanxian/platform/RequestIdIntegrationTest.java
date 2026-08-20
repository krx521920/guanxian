package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RequestIdIntegrationTest {
    private static final String HEADER = "X-Request-Id";

    @Autowired
    MockMvc mockMvc;

    @Test
    void safeInboundRequestIdIsEchoed() throws Exception {
        String requestId = "web:member-sync_2026.08-15";

        mockMvc.perform(get("/api/v1/health").header(HEADER, requestId))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, requestId));
    }

    @Test
    void missingAndUnsafeRequestIdsAreReplacedWithFreshUuids() throws Exception {
        String first = responseRequestId(get("/api/v1/health"));
        String second = responseRequestId(get("/api/v1/health").header(HEADER, "contains whitespace"));
        String third = responseRequestId(get("/api/v1/health").header(HEADER, "x".repeat(129)));
        String duplicate = responseRequestId(get("/api/v1/health").header(HEADER, "first", "second"));

        assertUuid(first);
        assertUuid(second);
        assertUuid(third);
        assertUuid(duplicate);
        assertNotEquals(first, second);
        assertNotEquals("contains whitespace", second);
        assertNotEquals("x".repeat(129), third);
        assertNotEquals("first", duplicate);
        assertNotEquals("second", duplicate);
    }

    @Test
    void mvcErrorsAndSecurityFailuresAlwaysCarryRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/members")
                        .header(HEADER, "malformed-json")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HEADER, "malformed-json"));

        mockMvc.perform(get("/api/v1/users/me").header(HEADER, "anonymous-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HEADER, "anonymous-401"));

        mockMvc.perform(get("/api/v1/dashboards/association")
                        .header(HEADER, "denied-403")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HEADER, "denied-403"));
    }

    private String responseRequestId(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String requestId = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().exists(HEADER))
                .andReturn()
                .getResponse()
                .getHeader(HEADER);
        assertNotNull(requestId);
        return requestId;
    }

    private static void assertUuid(String value) {
        assertTrue(UUID.fromString(value).toString().equals(value));
    }
}
