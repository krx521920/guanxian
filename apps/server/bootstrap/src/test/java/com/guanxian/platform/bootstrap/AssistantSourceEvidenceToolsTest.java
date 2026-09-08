package com.guanxian.platform.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guanxian.platform.ai.assistant.*;
import com.guanxian.platform.ai.rag.AiProviderProperties;
import com.guanxian.platform.ai.rag.RagProperties;
import com.guanxian.platform.shared.security.ActorScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.guanxian.platform.bootstrap.AssistantSourceEvidenceTools.*;

class AssistantSourceEvidenceToolsTest {
    final SourceDirectoryService directory = mock(SourceDirectoryService.class);
    final AssistantSourceEvidenceTools tools = new AssistantSourceEvidenceTools(directory);
    final ActorScope actor = new ActorScope(null, "fixture", "fixture", UUID.randomUUID(), null, Set.of("ASSOCIATION_ADMIN"), Set.of());
    final AssistantAccessContext access = new AssistantAccessContext(actor, Set.of("MEMBER_READ", "POLICY_READ"));
    ToolContext context() { return new ToolContext(Map.of(AssistantAccessContext.TOOL_CONTEXT_KEY, access, AssistantBusinessResults.CONTEXT_KEY, new AssistantBusinessResults())); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realSpringToolArgumentConversionAndStreamReceiptsUseServerContext(boolean streaming) {
        UUID id = UUID.randomUUID();
        var fields = new ObjectMapper().createObjectNode().put("记录状态", "候选公示").put("发布日期", "2026-09-08")
                .put("证据摘要", "待核对公开证据").put("private", "SECRET");
        var entry = new SourceDirectoryService.Entry(id, "SRC-TEST", "虚构·公告", null, fields, "2026-09-08T00:00:00Z", null, null,
                new SourceDirectoryService.Evidence("EV-TEST", "虚构·候选结果", "2026-09-08", "https://example.test/notice", List.of()));
        when(directory.search(eq(SourceDirectoryService.Kind.TENDER), eq("监测"), eq(0), eq(5), eq(actor), any(), any(), isNull()))
                .thenReturn(new SourceDirectoryService.Page(List.of(entry), 8, 0, 5));
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                var options = (ToolCallingChatOptions) prompt.getOptions();
                var callback = options.getToolCallbacks().stream().filter(c -> c.getToolDefinition().name().equals("search_tender_evidence")).findFirst().orElseThrow();
                assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("assistantAccess", "associationId", "ToolContext");
                String output = callback.call("{\"query\":{\"keyword\":\"监测\",\"period\":\"LAST_YEAR\",\"page\":0}}", new ToolContext(options.getToolContext()));
                assertThat(output).contains("SRC-TEST", "EV-TEST", "候选公示", "https://example.test/notice", "TENDER_EVIDENCE").doesNotContain("SECRET");
                return new ChatResponse(List.of(new Generation(new AssistantMessage("查询到一条展示记录。来源：SRC-TEST；不代表已中标。"))));
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("platformAssistantChatClient", ChatClient.builder(model).build());
        var provider = new AiProviderProperties(); provider.setEnabled(true); provider.setModel("fixture");
        var rag = new RagProperties(); rag.setExternalModelDataEgressEnabled(true);
        var client = new SpringAiAssistantChatClient(beans.getBeanProvider(ChatClient.class), List.of(tools), provider, rag);
        var request = new AssistantChatClient.CompletionRequest(access, "fixture-source", "查招标", "工作台", "/", "查招标");
        if (streaming) assertThat(client.stream(request).collectList().block()).isNotEmpty(); else client.complete(request);
        assertThat(request.businessResults().snapshot()).hasSize(1);
        assertThat(request.businessResults().snapshot().getFirst().items().getFirst().source().sourceId()).isEqualTo("SRC-TEST");
        assertThat(request.businessResults().snapshot().getFirst().items().getFirst().fields().get("金额及口径")).isEmpty();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        verify(directory).search(SourceDirectoryService.Kind.TENDER, "监测", 0, 5, actor, today.minusYears(1), today, null);
    }
    @Test void unavailableBackendIsNotReportedAsEmptyAndMissingAuthorityCannotRead() {
        when(directory.search(any(), anyString(), anyInt(), anyInt(), any(), any(), any(), any())).thenThrow(new IllegalStateException("SECRET database error"));
        var failed = tools.searchTenders(new SearchQuery("", Period.ALL, null, null, 0), context());
        assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.total()).isZero(); assertThat(failed.toString()).doesNotContain("SECRET");
        clearInvocations(directory);
        var denied = tools.searchActivities(new SearchQuery("", Period.ALL, null, null, 0), new ToolContext(Map.of(AssistantAccessContext.TOOL_CONTEXT_KEY,
                new AssistantAccessContext(actor, Set.of("POLICY_READ")))));
        assertThat(denied.status()).isEqualTo("FORBIDDEN"); verifyNoInteractions(directory);
        assertThatThrownBy(() -> tools.searchTenders(null, null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void toolBudgetIsSharedAndInvalidParametersCannotBroadenSearch() {
        var exhausted = new ToolContext(Map.of(AssistantAccessContext.TOOL_CONTEXT_KEY, access,
                AssistantToolBudget.CONTEXT_KEY, new AssistantToolBudget(8)));
        assertThatThrownBy(() -> tools.searchTenders(new SearchQuery("", Period.ALL, null, null, 0), exhausted)).isInstanceOf(RuntimeException.class);
        for (var query : List.of(new SearchQuery("x".repeat(201), Period.ALL, null, null, 0),
                new SearchQuery("", Period.ALL, null, null, -1), new SearchQuery("", Period.LAST_YEAR, "2026-01-01", null, 0),
                new SearchQuery("", Period.CUSTOM, "2026-09-08", "2026-01-01", 0)))
            assertThat(tools.searchTenders(query, context()).status()).isEqualTo("INVALID");
        verifyNoInteractions(directory);
    }
    @Test void localParserAsksForUncertainFiltersRatherThanDroppingNamedCompanyOrDate() {
        for (String message : List.of("查询某某公司的活动", "查2025年招标", "查询“甲企业”和“乙企业”招标", "查询“" + "甲".repeat(201) + "”招标", "推荐哪些招标符合资格", "删除这些活动记录")) {
            var answer = tools.answer(new AssistantLocalQueryProvider.LocalQueryRequest(access, message, "工作台", "/")).orElseThrow();
            assertThat(answer.businessResults()).isEmpty();
        }
        verifyNoInteractions(directory);
        assertThat(tools.answer(new AssistantLocalQueryProvider.LocalQueryRequest(access, "有哪些会员企业", "会员", "/members"))).isEmpty();
    }
    @Test void quotedCompanyOrKeywordDoesNotChangeTheRequestedRecordKind() {
        assertThat(sourceIntent("查询“某招标公司”的会员企业资料")).isFalse();
        when(directory.search(eq(SourceDirectoryService.Kind.TENDER), eq("最近活动发布"), eq(0), eq(5), eq(actor), isNull(), isNull(), isNull()))
                .thenReturn(new SourceDirectoryService.Page(List.of(), 0, 0, 5));
        var answer = tools.answer(new AssistantLocalQueryProvider.LocalQueryRequest(access, "查询“最近活动发布”招标资料", "工作台", "/")).orElseThrow();
        assertThat(answer.businessResults()).extracting(AssistantBusinessResults.Result::kind).containsExactly("TENDER_EVIDENCE");
        verify(directory).search(SourceDirectoryService.Kind.TENDER, "最近活动发布", 0, 5, actor, null, null, null);
        verifyNoMoreInteractions(directory);
    }
    @ParameterizedTest @ValueSource(strings = {"javascript:alert(1)", "https://u:p@example.test/a", "https://example.test/a;https://other.test/b", "https://example.test/with space", "file:///secret", "https://example.test/?url=http://other.test"})
    void unsafeSourceLinksAreNeverEmitted(String url) { assertThat(safeUrl(url)).isNull(); }
}
