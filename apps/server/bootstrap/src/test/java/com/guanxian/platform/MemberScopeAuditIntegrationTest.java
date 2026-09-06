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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MemberScopeAuditIntegrationTest {
    private static final String OWN_ENTERPRISE_ID = "00000000-0000-0000-0000-000000000201";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void enterpriseAdminCanOnlyUpdateOwnEnterpriseAndAssociationAdminReviewsWithAudit() throws Exception {
        JsonNode foreign = createMember("越权目标-" + suffix(), "SCOPE" + suffix(), "MEMBERS");
        String foreignId = foreign.path("id").asText();

        mockMvc.perform(put("/api/v1/members/{id}", foreignId)
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(foreign, "越权修改", foreign.path("visibility").asText())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_SCOPE_DENIED"));

        var ownResponse = mockMvc.perform(get("/api/v1/members/{id}", OWN_ENTERPRISE_ID)
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        JsonNode own = objectMapper.readTree(ownResponse.getContentAsByteArray()).path("data");
        String ownEtag = ownResponse.getHeader(HttpHeaders.ETAG);

        mockMvc.perform(put("/api/v1/members/{id}", OWN_ENTERPRISE_ID)
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .header(HttpHeaders.IF_MATCH, ownEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(own, own.path("name").asText(), own.path("visibility").asText())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROFILE_DRAFT_REQUIRED"));
        String reviewEtag = ownEtag;

        mockMvc.perform(put("/api/v1/members/{id}/review", OWN_ENTERPRISE_ID)
                        .with(httpBasic("association-operator", "operator123"))
                        .header(HttpHeaders.IF_MATCH, reviewEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        var reviewedResponse = mockMvc.perform(put("/api/v1/members/{id}/review", OWN_ENTERPRISE_ID)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, reviewEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACTIVE\",\"comment\":\"资料复核通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse();
        int reviewedVersion = objectMapper.readTree(
                reviewedResponse.getContentAsByteArray()).path("data").path("version").asInt();

        mockMvc.perform(get("/api/v1/audit-logs")
                        .queryParam("enterpriseId", OWN_ENTERPRISE_ID)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("MEMBER_REVIEW"))
                .andExpect(jsonPath("$.data[0].resourceVersion").value(reviewedVersion))
                .andExpect(jsonPath("$.data[0].actorUsername").value("association-admin"))
                .andExpect(jsonPath("$.data[0].requestId").isNotEmpty());

        deleteMember(foreignId, "\"0\"");
    }

    @Test
    void createAndDeleteAreBothRecordedInTheEnterpriseAuditTrail() throws Exception {
        JsonNode created = createMember("审计闭环-" + suffix(), "AUDIT" + suffix(), "MEMBERS");
        String id = created.path("id").asText();

        mockMvc.perform(get("/api/v1/audit-logs")
                        .queryParam("enterpriseId", id)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("MEMBER_CREATE"))
                .andExpect(jsonPath("$.data[0].resourceId").value(id));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-logs")
                        .queryParam("enterpriseId", id)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("MEMBER_DELETE"))
                .andExpect(jsonPath("$.data[0].resourceId").value(id))
                .andExpect(jsonPath("$.data[1].action").value("MEMBER_CREATE"));
    }

    @Test
    void enterpriseAdministratorCannotCreateAnUnownedEnterprise() throws Exception {
        String suffix = suffix();
        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "越权新建-" + suffix,
                                "unifiedSocialCreditCode", "CREATE" + suffix,
                                "category", "测试单位"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void privateEnterpriseIsHiddenFromOrdinaryMembersButVisibleToAssociationStaff() throws Exception {
        JsonNode created = createMember("私有资料-" + suffix(), "PRIVATE" + suffix(), "PRIVATE");
        String id = created.path("id").asText();

        mockMvc.perform(get("/api/v1/members/{id}", id)
                        .with(httpBasic("enterprise-member", "member123")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/members/{id}", id)
                        .with(httpBasic("association-operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"));

        deleteMember(id, "\"0\"");
    }

    private JsonNode createMember(String name, String creditCode, String visibility) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name,
                "unifiedSocialCreditCode", creditCode,
                "category", "测试单位",
                "visibility", visibility));
        String response = mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private String upsertBody(JsonNode source, String name, String visibility) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("unifiedSocialCreditCode", nullableText(source, "unifiedSocialCreditCode"));
        body.put("category", source.path("category").asText());
        body.put("address", nullableText(source, "address"));
        body.put("contactName", nullableText(source, "contactName"));
        body.put("contactPhone", nullableText(source, "contactPhone"));
        body.put("introduction", "权限边界回归测试");
        body.put("capabilities", source.path("capabilities"));
        body.put("products", source.path("products"));
        body.put("cooperationNeeds", source.path("cooperationNeeds"));
        body.put("visibility", visibility);
        body.put("status", source.path("status").asText());
        return objectMapper.writeValueAsString(body);
    }

    private static String nullableText(JsonNode source, String field) {
        JsonNode value = source.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private void deleteMember(String id, String etag) throws Exception {
        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, etag))
                .andExpect(status().isOk());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
