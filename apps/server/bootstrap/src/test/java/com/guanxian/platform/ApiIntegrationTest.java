package com.guanxian.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void healthIsPublicAndUsesEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void protectedEndpointReturnsUnifiedAuthenticationError() throws Exception {
        mockMvc.perform(get("/api/v1/members"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void currentUserContainsRolesAndPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("association-admin"))
                .andExpect(jsonPath("$.data.roles[0]").value("ASSOCIATION_ADMIN"))
                .andExpect(jsonPath("$.data.permissions").isArray());
    }

    @Test
    void assistantStreamUsesStructuredEventsAndStreamingHeaders() throws Exception {
        String conversationId = "20000000-0000-4000-8000-000000000001";
        var result = mockMvc.perform(post("/api/v1/assistant/chat/stream")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "conversationId": "%s",
                                  "message": "当前有哪些资料？",
                                  "maxCitations": 5,
                                  "pageTitle": "政策标准",
                                  "pagePath": "/policies"
                                }
                                """.formatted(conversationId)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string(containsString("\"type\":\"start\"")))
                .andExpect(content().string(containsString("\"type\":\"complete\"")))
                .andExpect(content().string(containsString(conversationId)));
    }

    @Test
    void assistantStreamQueriesScopedBusinessDataWhenModelIsDisabled() throws Exception {
        String conversationId = "20000000-0000-4000-8000-000000000002";
        var result = mockMvc.perform(post("/api/v1/assistant/chat/stream")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "conversationId": "%s",
                                  "message": "当前有哪些会员企业？",
                                  "maxCitations": 5,
                                  "pageTitle": "会员企业",
                                  "pagePath": "/members"
                                }
                                """.formatted(conversationId)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("LOCAL_BUSINESS_QUERY")))
                .andExpect(content().string(containsString("京城管网科技有限公司")))
                .andExpect(content().string(containsString("北方阀门制造有限公司")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("13800000001"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("91110000DEMO00001"))));
    }

    @Test
    void auditPagingReturnsStableSnapshotAndRejectsUnboundedOffsets() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs/page")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.snapshotId").isNumber());

        mockMvc.perform(get("/api/v1/audit-logs/page")
                        .param("page", "10001")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIT_PAGE"));

        mockMvc.perform(get("/api/v1/audit-logs/page")
                        .param("size", "501")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AUDIT_PAGE_SIZE"));
    }

    @Test
    void memberCrudWorksInMemory() throws Exception {
        String body = """
                {
                  "name": "测试管线技术有限公司",
                  "unifiedSocialCreditCode": "91110000TEST000001",
                  "category": "技术服务单位",
                  "address": "北京市朝阳区",
                  "contactName": "测试联系人",
                  "capabilities": ["管线探测"],
                  "products": ["管线探测服务"],
                  "cooperationNeeds": ["寻找运营单位"]
                }
                """;
        String response = mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        String id = json.path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/enterprises/{id}", id)
                .with(httpBasic("observer", "observer123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.name").value("测试管线技术有限公司"));

        String updatedBody = body.replace("测试管线技术有限公司", "测试管线科技有限公司");
        mockMvc.perform(put("/api/v1/members/{id}", id)
                .with(httpBasic("association-admin", "admin123"))
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatedBody))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.data.name").value("测试管线科技有限公司"));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                .with(httpBasic("association-admin", "admin123"))
                .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void validationErrorsUseUnifiedEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"category\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void policyMatchingAndDashboardsExposeFrontendRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/policies").with(httpBasic("observer", "observer123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").exists());

        mockMvc.perform(get("/api/v1/matches").with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));

        mockMvc.perform(get("/api/v1/collaborations").with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].progress").isNumber());

        mockMvc.perform(get("/api/v1/dashboards/association")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics").isArray())
                .andExpect(jsonPath("$.data.activities[*].type", hasItem("task")));

        mockMvc.perform(get("/api/v1/dashboards/enterprise")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendedPolicies").isArray());
    }

    @Test
    void matchingRequestCalculatesExplainableCandidates() throws Exception {
        String body = """
                {
                  "demandCompany": "测试需求企业",
                  "demandTitle": "燃气管道阀门与泄漏监测合作",
                  "scene": "燃气管网 · 运行监测",
                  "requirements": "需要阀门制造与泄漏预警能力",
                  "limit": 2
                }
                """;
        mockMvc.perform(post("/api/v1/ecosystem/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].reasons").isArray());
    }

    @Test
    void tiedMatchingCandidatesHaveStableOrderAndIdentifiers() throws Exception {
        String body = """
                {
                  "demandCompany": "通用需求方",
                  "demandTitle": "一般协作需求",
                  "scene": "综合场景",
                  "requirements": "",
                  "limit": 20
                }
                """;
        String bodyWithoutRequirements = """
                {
                  "demandCompany": "通用需求方",
                  "demandTitle": "一般协作需求",
                  "scene": "综合场景",
                  "limit": 20
                }
                """;

        byte[] first = mockMvc.perform(post("/api/v1/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(greaterThanOrEqualTo(2)))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] second = mockMvc.perform(post("/api/v1/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutRequirements))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        JsonNode firstData = objectMapper.readTree(first).path("data");
        JsonNode secondData = objectMapper.readTree(second).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(firstData, secondData);
        org.junit.jupiter.api.Assertions.assertEquals("京城管网科技有限公司",
                firstData.get(0).path("supplierCompany").asText());
        org.junit.jupiter.api.Assertions.assertEquals("北方阀门制造有限公司",
                firstData.get(1).path("supplierCompany").asText());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> java.util.UUID.fromString(firstData.get(0).path("id").asText()));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> java.util.UUID.fromString(firstData.get(1).path("id").asText()));
    }
}
