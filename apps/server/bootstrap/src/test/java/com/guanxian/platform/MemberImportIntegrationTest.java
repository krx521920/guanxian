package com.guanxian.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MemberImportIntegrationTest {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void templatePreviewAndCommitFormAReviewableImportLoop() throws Exception {
        byte[] template = mockMvc.perform(get("/api/v1/members/import-template")
                        .with(httpBasic("association-operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        byte[] survey = filledSurvey(template, suffix);
        MockMultipartFile file = new MockMultipartFile(
                "file", "会员调查-" + suffix + ".xlsx", XLSX, survey);

        String previewBody = mockMvc.perform(multipart("/api/v1/members/imports/preview")
                        .file(file)
                        .with(httpBasic("association-operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PREVIEWED"))
                .andExpect(jsonPath("$.data.templateVersion").value("GX-MEMBER-SURVEY-2026-01"))
                .andExpect(jsonPath("$.data.submittedUnit").value("测试提交单位"))
                .andExpect(jsonPath("$.data.sourceSha256").isNotEmpty())
                .andExpect(jsonPath("$.data.totalRows").value(2))
                .andExpect(jsonPath("$.data.validRows").value(1))
                .andExpect(jsonPath("$.data.invalidRows").value(1))
                .andExpect(jsonPath("$.data.rows[0].status").value("VALID"))
                .andExpect(jsonPath("$.data.rows[1].status").value("INVALID"))
                .andExpect(jsonPath("$.data.rows[1].errors[0]").exists())
                .andReturn().getResponse().getContentAsString();
        String batchId = objectMapper.readTree(previewBody).path("data").path("batchId").asText();

        String commitBody = mockMvc.perform(post("/api/v1/members/imports/{batchId}/commit", batchId)
                        .with(httpBasic("association-operator", "operator123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.importedRows").value(1))
                .andExpect(jsonPath("$.data.invalidRows").value(1))
                .andExpect(jsonPath("$.data.enterpriseIds.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String enterpriseId = objectMapper.readTree(commitBody)
                .path("data").path("enterpriseIds").get(0).asText();

        mockMvc.perform(get("/api/v1/members/{id}", enterpriseId)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.data.name").value("导入企业-" + suffix))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(get("/api/v1/members/{id}/provenance", enterpriseId)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceFilename").value("会员调查-" + suffix + ".xlsx"))
                .andExpect(jsonPath("$.data.submittedUnit").value("测试提交单位"))
                .andExpect(jsonPath("$.data.templateVersion").value("GX-MEMBER-SURVEY-2026-01"));

        mockMvc.perform(post("/api/v1/members/imports/{batchId}/commit", batchId)
                        .with(httpBasic("association-operator", "operator123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .queryParam("enterpriseId", enterpriseId)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("MEMBER_IMPORT_CREATE"));

        String auditBody = mockMvc.perform(get("/api/v1/audit-logs")
                        .queryParam("limit", "500")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        boolean previewAudited = false;
        boolean commitAudited = false;
        for (JsonNode entry : objectMapper.readTree(auditBody).path("data")) {
            if (!batchId.equals(entry.path("resourceId").asText())) continue;
            previewAudited |= "MEMBER_IMPORT_PREVIEW".equals(entry.path("action").asText());
            commitAudited |= "MEMBER_IMPORT_COMMIT".equals(entry.path("action").asText());
        }
        org.junit.jupiter.api.Assertions.assertTrue(previewAudited, "preview must be audited");
        org.junit.jupiter.api.Assertions.assertTrue(commitAudited, "commit must be audited");

        mockMvc.perform(delete("/api/v1/members/{id}", enterpriseId)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    @Test
    void commitRevalidatesAgainstMembersCreatedAfterPreview() throws Exception {
        byte[] template = mockMvc.perform(get("/api/v1/members/import-template")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        MockMultipartFile file = new MockMultipartFile(
                "file", "race-" + suffix + ".xlsx", XLSX, filledSurvey(template, suffix));
        String previewBody = mockMvc.perform(multipart("/api/v1/members/imports/preview")
                        .file(file).with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batchId = objectMapper.readTree(previewBody).path("data").path("batchId").asText();

        String memberBody = objectMapper.writeValueAsString(Map.of(
                "name", "导入企业-" + suffix,
                "unifiedSocialCreditCode", "IMPORT" + suffix,
                "category", "技术服务单位"));
        String createdBody = mockMvc.perform(post("/api/v1/members")
                        .with(httpBasic("association-admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String enterpriseId = objectMapper.readTree(createdBody).path("data").path("id").asText();

        mockMvc.perform(post("/api/v1/members/imports/{batchId}/commit", batchId)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));

        mockMvc.perform(delete("/api/v1/members/{id}", enterpriseId)
                        .with(httpBasic("system-admin", "system123"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    @Test
    void importRejectsUnauthorizedAndMalformedUploads() throws Exception {
        mockMvc.perform(get("/api/v1/members/import-template")
                        .with(httpBasic("enterprise-admin", "enterprise123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        MockMultipartFile invalid = new MockMultipartFile(
                "file", "not-an-xlsx.xlsx", XLSX, "not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/members/imports/preview")
                        .file(invalid)
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_IMPORT"));

        byte[] template = mockMvc.perform(get("/api/v1/members/import-template")
                        .with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertRejectedWorkbook(template, workbook -> workbook.getSheet("会员资料")
                .createRow(501).createCell(0).setCellValue("稀疏超限行"));
        assertRejectedWorkbook(template, workbook -> workbook.getSheet("会员资料")
                .getRow(0).getCell(1).setCellValue("企业名称*"));
        assertRejectedWorkbook(template, workbook -> {
            for (int index = 0; index < 4; index++) workbook.createSheet("额外工作表" + index);
        });
    }

    private void assertRejectedWorkbook(
            byte[] template, java.util.function.Consumer<XSSFWorkbook> mutation) throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            mutation.accept(workbook);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "rejected.xlsx", XLSX, bytes);
        mockMvc.perform(multipart("/api/v1/members/imports/preview")
                        .file(file).with(httpBasic("association-admin", "admin123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMBER_IMPORT"));
    }

    private static byte[] filledSurvey(byte[] template, String suffix) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheet("会员资料");
            workbook.getSheet("提交信息").getRow(1).getCell(1).setCellValue("测试提交单位");
            var valid = sheet.createRow(1);
            valid.createCell(0).setCellValue("导入企业-" + suffix);
            valid.createCell(1).setCellValue("IMPORT" + suffix);
            valid.createCell(2).setCellValue("技术服务单位");
            valid.createCell(4).setCellValue("导入联系人");
            valid.createCell(6).setCellValue("contact@example.test");
            valid.createCell(7).setCellValue("会员企业简介");
            valid.createCell(8).setCellValue("管线监测；数字孪生");
            valid.createCell(9).setCellValue("监测平台");
            valid.createCell(10).setCellValue("监测服务");
            valid.createCell(11).setCellValue("燃气管线；供水管线");
            valid.createCell(12).setCellValue("寻找场景合作方");
            valid.createCell(13).setCellValue("MEMBERS");

            var invalid = sheet.createRow(2);
            invalid.createCell(0).setCellValue("缺少分类-" + suffix);
            invalid.createCell(1).setCellValue("INVALID" + suffix);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
