package com.guanxian.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiRobustnessIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void unknownMemberReturnsUnifiedNotFoundResponse() throws Exception {
        UUID unknownId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/members/{id}", unknownId)
                        .with(httpBasic("observer", "observer123")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("member not found: " + unknownId));
    }

    @Test
    void unknownRouteReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/route-that-does-not-exist")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("resource not found"));
    }

    @Test
    void malformedJsonReturns400InsteadOfServerError() throws Exception {
        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"broken\","))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void invalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/members/not-a-uuid")
                        .with(httpBasic("observer", "observer123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.message").value("invalid parameter: id"));
    }

    @Test
    void unsupportedMethodUsesUnified405Response() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMemberStatusIsRejectedAsAFieldViolation() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "非法状态测试企业",
                "category", "测试单位",
                "status", "ARCHIVED"));

        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[*].field", hasItem("status")));
    }

    @Test
    void oversizedScalarAndCollectionValuesAreRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "企".repeat(201),
                "category", "测试单位",
                "introduction", "介".repeat(2001),
                "capabilities", List.of("能".repeat(101)),
                "cooperationNeeds", List.of("需".repeat(201))));

        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[*].field", hasItem("name")))
                .andExpect(jsonPath("$.data[*].field", hasItem("introduction")))
                .andExpect(jsonPath("$.data[*].field", hasItem("capabilities[0]")))
                .andExpect(jsonPath("$.data[*].field", hasItem("cooperationNeeds[0]")));
    }

    @Test
    void oversizedMemberCollectionsAreRejected() throws Exception {
        List<String> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> "条目-" + index)
                .toList();
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "集合上限测试企业",
                "category", "测试单位",
                "capabilities", tooMany,
                "products", tooMany,
                "cooperationNeeds", tooMany));

        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[*].field", hasItem("capabilities")))
                .andExpect(jsonPath("$.data[*].field", hasItem("products")))
                .andExpect(jsonPath("$.data[*].field", hasItem("cooperationNeeds")));
    }

    @Test
    void invalidMatchingBoundsAreRejected() throws Exception {
        String body = """
                {
                  "demandCompany": "测试企业",
                  "demandTitle": "测试需求",
                  "scene": "运行监测",
                  "requirements": "测试",
                  "limit": 0
                }
                """;

        mockMvc.perform(post("/api/v1/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[*].field", hasItem("limit")));
    }

    @Test
    void blankAndOversizedMatchingFieldsAreRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "demandCompany", " ",
                "demandTitle", "需".repeat(301),
                "scene", "场".repeat(101),
                "requirements", "求".repeat(1001),
                "limit", 21));

        mockMvc.perform(post("/api/v1/ecosystem/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.length()").value(5));
    }
}
