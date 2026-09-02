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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MemberCrudPermissionIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void fullCrudFlowHonoursReadWriteAndDeleteBoundaries() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String creditCode = "TEST" + suffix;
        String body = memberJson("正向流程企业-" + suffix, creditCode);

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-operator", "operator123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.capabilities", hasItem("管线探测")))
                .andExpect(jsonPath("$.data.capabilities.length()").value(1))
                .andExpect(jsonPath("$.data.products.length()").value(1))
                .andExpect(jsonPath("$.data.cooperationNeeds.length()").value(1))
                .andReturn().getResponse().getContentAsString());
        String id = created.path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/enterprises/{id}", id)
                .with(httpBasic("observer", "observer123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.name").value("正向流程企业-" + suffix));

        mockMvc.perform(get("/api/v1/members")
                        .queryParam("q", suffix)
                        .with(httpBasic("enterprise-member", "member123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].name").value("正向流程企业-" + suffix))
                .andExpect(jsonPath("$.data[0].shortName").value(("正向流程企业-" + suffix).substring(0, 10)))
                .andExpect(jsonPath("$.data[0].role").value("技术服务单位"))
                .andExpect(jsonPath("$.data[0].scenes[0]").value("管线探测"))
                .andExpect(jsonPath("$.data[0].products[0]").value("探测服务"))
                .andExpect(jsonPath("$.data[0].city").value("北京市朝阳区"))
                .andExpect(jsonPath("$.data[0].contact").value("测试联系人"))
                .andExpect(jsonPath("$.data[0].completeness").value(73))
                .andExpect(jsonPath("$.data[0].status").value("待审核"))
                .andExpect(jsonPath("$.data[0].updatedAt").exists());

        String updated = memberJson("更新后的企业-" + suffix, creditCode);
        mockMvc.perform(put("/api/v1/enterprises/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updated))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.name").value("更新后的企业-" + suffix))
                .andExpect(jsonPath("$.data.id").value(id));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("association-operator", "operator123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.id").value(id));

        mockMvc.perform(get("/api/v1/members/{id}", id)
                        .with(httpBasic("observer", "observer123")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/members/{id}", id)
                        .queryParam("includeDeleted", "true")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.data.status").value("DELETED"))
                .andExpect(jsonPath("$.data.deletedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.statusBeforeDelete").value("ACTIVE"));

        mockMvc.perform(put("/api/v1/members/{id}/restore", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.data.name").value("更新后的企业-" + suffix))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());
    }

    @Test
    void duplicateCreditCodeReturnsConflictAndDoesNotOverwriteOriginal() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String creditCode = "DUP" + suffix;
        String firstBody = memberJson("原企业-" + suffix, "  " + creditCode.toLowerCase() + "  ");
        String duplicateBody = memberJson("重复企业-" + suffix, creditCode);

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String id = created.path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));

        mockMvc.perform(get("/api/v1/members/{id}", id)
                .with(httpBasic("observer", "observer123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("原企业-" + suffix))
                .andExpect(jsonPath("$.data.unifiedSocialCreditCode").value(creditCode));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    @Test
    void updateCannotTakeAnotherMembersNormalizedCreditCode() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        JsonNode first = createAsAssociationAdmin(memberJson("信用代码企业一-" + suffix, "UPA" + suffix));
        JsonNode second = createAsAssociationAdmin(memberJson("信用代码企业二-" + suffix, "UPB" + suffix));
        String firstId = first.path("data").path("id").asText();
        String secondId = second.path("data").path("id").asText();

        mockMvc.perform(put("/api/v1/members/{id}", secondId)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson("信用代码企业二-" + suffix, "  upa" + suffix.toLowerCase() + "  ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));

        mockMvc.perform(get("/api/v1/members/{id}", secondId)
                        .with(httpBasic("observer", "observer123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unifiedSocialCreditCode").value("UPB" + suffix));

        mockMvc.perform(delete("/api/v1/members/{id}", firstId)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/members/{id}", secondId)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    @Test
    void matchingScoreAndReasonsReflectTagsCapabilitiesAndLocalDelivery() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String companyName = "精确匹配企业-" + suffix;
        String body = objectMapper.writeValueAsString(Map.of(
                "name", companyName,
                "unifiedSocialCreditCode", "MATCH" + suffix,
                "category", "装备制造",
                "address", "北京市大兴区",
                "capabilities", List.of("阀门设备", "泄漏监测"),
                "products", List.of("智能阀门监测系统")));

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String id = created.path("data").path("id").asText();

        String matchBody = """
                {
                  "demandCompany": "需求方企业",
                  "demandTitle": "阀门泄漏治理",
                  "scene": "燃气管网",
                  "requirements": "需要阀门设备与泄漏监测能力",
                  "limit": 1
                }
                """;
        byte[] firstMatchResponse = mockMvc.perform(post("/api/v1/ecosystem/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].supplierCompany").value(companyName))
                .andExpect(jsonPath("$.data[0].solution").value("智能阀门监测系统"))
                .andExpect(jsonPath("$.data[0].score").value(96))
                .andExpect(jsonPath("$.data[0].reasons.length()").value(5))
                .andExpect(jsonPath("$.data[0].state").value("待确认"))
                .andReturn().getResponse().getContentAsByteArray();

        byte[] secondMatchResponse = mockMvc.perform(post("/api/v1/ecosystem/matches")
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode firstData = objectMapper.readTree(firstMatchResponse).path("data");
        JsonNode secondData = objectMapper.readTree(secondMatchResponse).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(firstData, secondData);

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    private String memberJson(String name, String creditCode) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", name,
                "unifiedSocialCreditCode", creditCode,
                "category", "技术服务单位",
                "address", "北京市朝阳区",
                "contactName", "测试联系人",
                "capabilities", List.of(" 管线探测 ", "管线探测", ""),
                "products", List.of("探测服务"),
                "cooperationNeeds", List.of("寻找运营单位"),
                "status", " active "));
    }

    private JsonNode createAsAssociationAdmin(String body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
    }
}
