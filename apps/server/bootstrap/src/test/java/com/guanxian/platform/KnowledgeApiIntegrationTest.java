package com.guanxian.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void ingestsPolicyAndAnswersWithSourceCitationWithoutExternalModel() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents/text")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "地下管线安全管理办法",
                                  "documentType": "POLICY",
                                  "sourceType": "MANUAL_TEXT",
                                  "sourceUrl": "https://example.org/policies/pipeline-safety",
                                  "visibility": "ASSOCIATION",
                                  "status": "PUBLISHED",
                                  "content": "地下管线运行单位应当建立巡检制度。高压燃气管线应加强泄漏监测并保留巡检记录。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/v1/knowledge/questions")
                        .with(httpBasic("enterprise-member", "member123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "高压燃气管线需要采取什么措施？",
                                  "maxCitations": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("RETRIEVAL_SUMMARY"))
                .andExpect(jsonPath("$.data.citations.length()").value(greaterThan(0)))
                .andExpect(jsonPath("$.data.citations[0].documentName").value("地下管线安全管理办法"))
                .andExpect(jsonPath("$.data.citations[0].source")
                        .value("https://example.org/policies/pipeline-safety"));
    }
}
