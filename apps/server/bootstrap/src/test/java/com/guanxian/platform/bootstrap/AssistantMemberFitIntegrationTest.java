package com.guanxian.platform.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.assistant.PlatformAssistantService;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import com.guanxian.platform.shared.error.ForbiddenException;
import com.guanxian.platform.shared.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual MVC validation/security + actual shared fit rules; only repository/identity/model boundaries mocked. */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantMemberFitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean MemberService members;
    @MockitoBean ActorScopeResolver scopes;
    @MockitoBean PlatformAssistantService model;
    final UUID association = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
    final ActorScope actor = new ActorScope(null, "fixture", "fixture", association, null, Set.of("ASSOCIATION_OPERATOR"), Set.of());
    final List<Map<String, String>> criteria = List.of(Map.of("field", "capabilities", "value", "监测"),
            Map.of("field", "category", "value", "设备制造"), Map.of("field", "services", "value", "抢修"));

    @BeforeEach void setUp() { when(scopes.resolve(any())).thenReturn(actor); }
    @AfterEach void noInference() { verifyNoInteractions(model); }

    @Test void manualCriteriaUseFreshScopedRecordsAndNeverCallModel() throws Exception {
        when(members.get(first, actor)).thenReturn(member(first, "监测"), member(first, "测绘"));
        var before = mvc.perform(auth(body(List.of(first), criteria))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.result.kind").value("MEMBER_FIT_CHECK"))
                .andExpect(jsonPath("$.data.result.filters.条件来源").value("用户手动确认"))
                .andExpect(jsonPath("$.data.result.items[0].evidence[0].state").value("MATCHED"))
                .andExpect(jsonPath("$.data.result.items[0].evidence[1].state").value("UNMET"))
                .andExpect(jsonPath("$.data.result.items[0].evidence[2].state").value("INSUFFICIENT"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(before).doesNotContain("SECRET", "13800000000");
        var after = mvc.perform(auth(body(List.of(first), criteria))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.items[0].evidence[0].state").value("INSUFFICIENT"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json.readTree(before).at("/data/result/id")).isNotEqualTo(json.readTree(after).at("/data/result/id"));
        verify(members, times(2)).get(first, actor); verifyNoMoreInteractions(members);
    }
    @Test void lackOfAuthenticationOrMemberAuthorityNeverReachesRecords() throws Exception {
        mvc.perform(post("/api/v1/assistant/members/fit-check").contentType(MediaType.APPLICATION_JSON).content(body(List.of(first), criteria))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/assistant/members/fit-check").with(jwt().authorities(new SimpleGrantedAuthority("POLICY_READ")))
                .contentType(MediaType.APPLICATION_JSON).content(body(List.of(first), criteria))).andExpect(status().isForbidden());
        verifyNoInteractions(members);
    }
    @Test void associationCannotBeInjectedAndUnscopedSystemAdminMustSelectContext() throws Exception {
        mvc.perform(auth(json.writeValueAsString(Map.of("associationId", UUID.randomUUID(), "enterpriseIds", List.of(first), "criteria", criteria))))
                .andExpect(status().isForbidden());
        when(scopes.resolve(any())).thenReturn(new ActorScope(null, "fixture", "fixture", null, null, Set.of("SYSTEM_ADMIN"), Set.of()));
        mvc.perform(auth(body(List.of(first), criteria))).andExpect(status().isForbidden());
        verifyNoInteractions(members);
    }
    @Test void oneForbiddenRecordDiscardsAllPartialDataAndSanitizesError() throws Exception {
        when(members.get(first, actor)).thenReturn(member(first, "监测"));
        when(members.get(second, actor)).thenThrow(new ForbiddenException("FORBIDDEN", "SECRET reason"));
        mvc.perform(auth(body(List.of(first, second), criteria))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.result.items").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }
    @Test void malformedBoundsFieldsStatusAndDuplicatesFailBeforeRead() throws Exception {
        var requests = List.of(body(List.of(first, first), criteria), body(List.of(), criteria),
                body(java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList(), criteria),
                body(List.of(first), List.of()), body(List.of(first), Collections.nCopies(9, criteria.getFirst())),
                body(List.of(first), List.of(Map.of("field", "contactPhone", "value", "SECRET"))),
                body(List.of(first), List.of(Map.of("field", "services", "value", " "))),
                body(List.of(first), List.of(Map.of("field", "services", "value", "字".repeat(81)))),
                body(List.of(first), List.of(Map.of("field", "status", "value", "INVALID"))),
                body(List.of(first), List.of(Map.of("field", "services", "value", " FIXTURE "), Map.of("field", "services", "value", "fixture"))),
                "{\"enterpriseIds\":[null],\"criteria\":[null]}", "{\"enterpriseIds\":[\"not-a-uuid\"],\"criteria\":[]}");
        for (String request : requests) mvc.perform(auth(request)).andExpect(status().isBadRequest());
        verifyNoInteractions(members);
    }
    @Test void preservesOrderAndIgnoresClientSuppliedFactsRolesAndScores() throws Exception {
        when(members.get(first, actor)).thenReturn(member(first, "监测")); when(members.get(second, actor)).thenReturn(member(second, "咨询"));
        var payload = json.readTree(body(List.of(second, first), criteria));
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("capabilities", "SECRET invented").put("roles", "SYSTEM_ADMIN").put("score", 100);
        mvc.perform(auth(json.writeValueAsString(payload))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.data.result.items[1].id").value(first.toString()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
        verify(members).get(second, actor); verify(members).get(first, actor); verifyNoMoreInteractions(members);
    }
    @Test void backendFailureIsNotEmptySuccess() throws Exception {
        when(members.get(first, actor)).thenThrow(new IllegalStateException("SECRET storage error"));
        mvc.perform(auth(body(List.of(first), criteria))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.status").value("FAILED"))
                .andExpect(jsonPath("$.data.result.items").isEmpty());
    }
    private String body(List<UUID> ids, Object conditions) throws Exception { return json.writeValueAsString(Map.of("associationId", association, "enterpriseIds", ids, "criteria", conditions)); }
    private MockHttpServletRequestBuilder auth(String body) { return post("/api/v1/assistant/members/fit-check").with(httpBasic("association-admin", "admin123")).contentType(MediaType.APPLICATION_JSON).content(body); }
    private MemberProfile member(UUID id, String capability) { return new MemberProfile(id, association, "虚构企业", "SECRET", "技术服务", "虚构地址", "SECRET", "13800000000", "SECRET", "简介", List.of(capability), List.of(), List.of(), List.of(), List.of(), "MEMBERS", "ACTIVE", 1, Instant.EPOCH, Instant.EPOCH, null, null, null); }
}
