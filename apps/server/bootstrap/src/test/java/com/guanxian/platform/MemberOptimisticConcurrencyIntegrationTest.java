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
class MemberOptimisticConcurrencyIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void etagIncrementsAndStaleWritesCannotChangeOrDeleteAliasResource() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String original = body("版本企业-" + suffix, "VER" + suffix);
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/enterprises")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(original))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsByteArray());
        String id = created.path("data").path("id").asText();

        String updated = body("已更新版本企业-" + suffix, "VER" + suffix);
        mockMvc.perform(put("/api/v1/enterprises/{id}", id)
                        .with(httpBasic("enterprise-admin", "enterprise123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updated))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(original))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        mockMvc.perform(delete("/api/v1/enterprises/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        mockMvc.perform(get("/api/v1/members/{id}", id)
                        .with(httpBasic("observer", "observer123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.data.name").value("已更新版本企业-" + suffix))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk());
    }

    @Test
    void missingAndMalformedIfMatchUseStableErrors() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        JsonNode created = create(body("前置条件企业-" + suffix, "PRE" + suffix));
        String id = created.path("data").path("id").asText();
        String update = body("前置条件更新企业-" + suffix, "PRE" + suffix);

        mockMvc.perform(put("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        for (String invalid : new String[]{"*", "W/\"0\"", "0", "\"-1\"", "\"01\"", "\"9223372036854775808\""}) {
            mockMvc.perform(put("/api/v1/members/{id}", id)
                            .with(httpBasic("association-admin", "admin123"))
                            .header(HttpHeaders.IF_MATCH, invalid)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(update))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        }

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\"", "\"1\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));

        mockMvc.perform(delete("/api/v1/members/{id}", id)
                        .with(httpBasic("association-admin", "admin123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    private JsonNode create(String body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn().getResponse().getContentAsByteArray());
    }

    private String body(String name, String creditCode) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "name", name,
                "unifiedSocialCreditCode", creditCode,
                "category", "测试单位"));
    }
}
