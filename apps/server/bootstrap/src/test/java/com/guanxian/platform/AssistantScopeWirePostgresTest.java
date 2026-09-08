package com.guanxian.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.shared.security.ActorScope;
import com.guanxian.platform.shared.security.ActorScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL + MVC SSE bytes with the production Jackson inclusion setting. No model egress. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.flyway.enabled=true", "guanxian.business.repository=postgres",
        "guanxian.member.repository=postgres", "guanxian.member.seed-demo-data=false", "guanxian.security.mode=demo",
        "spring.jackson.default-property-inclusion=non_null", "guanxian.ai.provider.enabled=false",
        "guanxian.ai.rag.external-model-data-egress-enabled=false"})
@AutoConfigureMockMvc
class AssistantScopeWirePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("guanxian").withUsername("guanxian").withPassword("test-only-password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoSpyBean ActorScopeResolver scopes;
    UUID first, second;

    @BeforeEach void seed() {
        first = UUID.randomUUID(); second = UUID.randomUUID();
        for (UUID association : List.of(first, second)) {
            jdbc.update("INSERT INTO association(id,name) VALUES (?,?)", association, "虚构回归协会" + association);
            jdbc.update("INSERT INTO enterprise(id,association_id,name,category,status,visibility) VALUES (?,?,?,'技术服务','ACTIVE','PRIVATE')",
                    UUID.randomUUID(), association, "虚构回归企业" + association);
        }
    }
    private void scope(String username, UUID association, String role) {
        doReturn(new ActorScope(null, "wire-test", username, association, null, Set.of(role), Set.of()))
                .when(scopes).resolve(argThat(a -> a != null && a.getName().equals(username)));
    }
    private String body() {
        return "{\"conversationId\":\"" + UUID.randomUUID() + "\",\"message\":\"现在协会有多少家企业\",\"pageTitle\":\"协会工作台\",\"pagePath\":\"/\"}";
    }
    private List<JsonNode> stream(String username, String password) throws Exception {
        var request = mvc.perform(post("/api/v1/assistant/chat/stream").with(httpBasic(username, password))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(body()))
                .andExpect(request().asyncStarted()).andReturn();
        String wire = mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<JsonNode> events = new ArrayList<>();
        for (String line : wire.split("\\R")) if (line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
        assertThat(events).isNotEmpty();
        assertThat(events.getLast().path("type").asText()).isEqualTo("complete");
        return events;
    }
    @Test void unselectedAdminCompletesGlobalQueryAndSelectedScopeStillRestrictsResults() throws Exception {
        scope("system-admin", null, "SYSTEM_ADMIN");
        var events = stream("system-admin", "system123");
        var answer = events.getLast().path("answer");
        assertThat(answer.path("mode").asText()).isEqualTo("LOCAL_BUSINESS_QUERY");
        assertThat(answer.path("modelConnected").asBoolean()).isFalse();
        var receipt = answer.path("businessResults").get(0);
        assertThat(receipt.has("associationId")).isTrue();
        assertThat(receipt.get("associationId").isNull()).isTrue();
        assertThat(receipt.path("scope").asText()).contains("全部协会");
        assertThat(receipt.path("total").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(receipt.toString()).contains(first.toString(), second.toString());
        for (var event : events) for (var partial : event.path("businessResults"))
            assertThat(partial).isEqualTo(receipt);
        scope("system-admin", first, "SYSTEM_ADMIN");
        var scoped = stream("system-admin", "system123").getLast().path("answer").path("businessResults").get(0);
        assertThat(scoped.path("associationId").asText()).isEqualTo(first.toString());
        assertThat(scoped.path("total").asInt()).isEqualTo(1);
        assertThat(scoped.toString()).doesNotContain(second.toString());
    }
    @Test void missingScopeDoesNotGrantGlobalAccessToOrdinaryOrAnonymousUsers() throws Exception {
        scope("association-admin", null, "ASSOCIATION_ADMIN");
        mvc.perform(post("/api/v1/assistant/chat/stream").with(httpBasic("association-admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/assistant/chat/stream").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
    }
}
