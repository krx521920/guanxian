package com.guanxian.platform.ai.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic harness contracts, NOT a live-model answer-quality score. */
class AssistantReasoningContractTest {
    @Test void selectedPreflightCountsAgainstTheSameEightQueryBudget() {
        var context = new ToolContext(java.util.Map.of(AssistantToolBudget.CONTEXT_KEY, new AssistantToolBudget(1)));
        for (int i = 0; i < 7; i++) AssistantToolBudget.consume(context);
        assertThrows(RuntimeException.class, () -> AssistantToolBudget.consume(context));
        assertThrows(IllegalArgumentException.class, () -> new AssistantToolBudget(9));
    }
    @Test void toolBudgetIsSharedWithinOneTurnButNotAcrossTurns() {
        var first = new ToolContext(java.util.Map.of(AssistantToolBudget.CONTEXT_KEY, new AssistantToolBudget()));
        for (int i = 0; i < 8; i++) AssistantToolBudget.consume(first);
        assertThrows(RuntimeException.class, () -> AssistantToolBudget.consume(first));
        assertDoesNotThrow(() -> AssistantToolBudget.consume(new ToolContext(java.util.Map.of(AssistantToolBudget.CONTEXT_KEY, new AssistantToolBudget()))));
    }
    @Test void detailFollowsLatestExplicitUserRequest() {
        assertEquals(AssistantResponsePolicy.Detail.BRIEF, AssistantResponsePolicy.select("详细解释，最后只要结论", "DETAILED").detail());
        assertEquals(AssistantResponsePolicy.Detail.DETAILED, AssistantResponsePolicy.select("一句话不够，请详细解释", "BRIEF").detail());
        assertEquals(AssistantResponsePolicy.Detail.STANDARD, AssistantResponsePolicy.select("现在有几家企业？", "AUTO").detail());
        assertThrows(IllegalArgumentException.class, () -> AssistantResponsePolicy.select("问题", "EXECUTE"));
    }

    @Test void complexWorkGetsOrganizationWithoutInventedExecution() {
        var policy = AssistantResponsePolicy.select("先查会员现状，然后对比需求，给出完整方案和验收条件", "AUTO");
        assertTrue(policy.multiStep());
        assertEquals(AssistantResponsePolicy.Detail.DETAILED, policy.detail());
        assertTrue(policy.instructions().contains("不能标为已完成"));
        assertFalse(AssistantResponsePolicy.select("有几条？", "BRIEF").multiStep());
    }

    @Test void thirtyTurnsKeepAnchorAndLatestCorrectionButBoundHistory() {
        var memory = new AssistantConversationMemory(2);
        memory.add("user-A:association-A:provider-1", pair("为客户演示，只用虚构数据", "先查询现状。"));
        for (int i = 0; i < 30; i++) memory.add("user-A:association-A:provider-1", pair("补充问题" + i, "回答" + i));
        memory.add("user-A:association-A:provider-1", pair("更正：只看监测企业，不看施工企业", "已了解筛选要求。"));
        var history = memory.get("user-A:association-A:provider-1");
        assertEquals(9, history.size());
        assertTrue(history.getFirst().getText().contains("只用虚构数据"));
        assertTrue(history.get(history.size() - 2).getText().contains("更正"));
        assertTrue(memory.get("user-B:association-A:provider-1").isEmpty());
        assertTrue(memory.get("user-A:association-B:provider-1").isEmpty());
        assertTrue(memory.get("user-A:association-A:provider-2").isEmpty());
        memory.clear("user-A:association-A:provider-1");
        assertTrue(memory.get("user-A:association-A:provider-1").isEmpty());
    }

    @Test void memoryEvictsAndClipsWithoutFabricatingASummary() {
        var memory = new AssistantConversationMemory(1);
        memory.add("first", pair("问题", "回答"));
        memory.add("second", pair("用户约束".repeat(1000), "很长的回答".repeat(1000)));
        assertTrue(memory.get("first").isEmpty());
        assertTrue(memory.get("second").getFirst().getText().contains("已截短"));
        assertThrows(IllegalArgumentException.class, () -> memory.add("second", List.of(new UserMessage("未完成"))));
        var fitted = AssistantMemoryAdvisor.fitHistory(memory.get("second"), 20);
        assertTrue(fitted.isEmpty());
    }

    @Test void streamedSuccessfulTurnStoresRawQuestionNotRetrievedEvidence() {
        var memory = new AssistantConversationMemory(5);
        var model = new FakeModel();
        var client = client(model, memory);
        ask(client, "user-A", "原问题：只看虚构数据", "资料证据 SECRET_RETRIEVAL").stream().content().blockLast();
        assertFalse(memory.get("user-A").toString().contains("SECRET_RETRIEVAL"));
        assertTrue(memory.get("user-A").toString().contains("原问题"));
        ask(client, "user-A", "那下一步呢", "新的检索").call().content();
        assertTrue(model.prompts.getLast().getInstructions().stream().anyMatch(message -> message.getText().contains("只看虚构数据")));
        assertFalse(model.prompts.getLast().toString().contains("SECRET_RETRIEVAL"));
    }

    @Test void errorsCancellationAndEmptyAnswersNeverEnterMemory() {
        var memory = new AssistantConversationMemory(5);
        var model = new FakeModel();
        var client = client(model, memory);
        model.stream = () -> Flux.concat(Flux.just(response("半句话")), Flux.error(new IllegalStateException("fixture failure")));
        assertThrows(RuntimeException.class, () -> ask(client, "error", "问题", "依据").stream().content().blockLast());
        assertTrue(memory.get("error").isEmpty());
        model.stream = () -> Flux.concat(Flux.just(response("已收到")), Flux.never());
        ask(client, "cancel", "问题", "依据").stream().content().take(1).blockLast();
        assertTrue(memory.get("cancel").isEmpty());
        model.stream = () -> Flux.just(response(""));
        assertThrows(RuntimeException.class, () -> ask(client, "empty", "问题", "依据").stream().content().blockLast());
        assertTrue(memory.get("empty").isEmpty());
    }

    @Test void oversizedInputIsRejectedBeforeModelAndOutputBeforeMemoryCommit() {
        var memory = new AssistantConversationMemory(5);
        var model = new FakeModel();
        var client = client(model, memory);
        assertThrows(RuntimeException.class, () -> ask(client, "large", "问题", "超长".repeat(4000)).call().content());
        assertTrue(model.prompts.isEmpty());
        model.stream = () -> Flux.just(response("字".repeat(801)));
        assertThrows(RuntimeException.class, () -> ask(client, "large-output", "问题", "依据").stream().content().blockLast());
        assertTrue(memory.get("large-output").isEmpty());
    }

    @Test void outputAccountingHandlesSplitUnicodeAndWhitespaceFlood() {
        var buffer = new AssistantOutputBuffer(5);
        buffer.append("你好"); buffer.append("\uD83D"); buffer.append("\uDE00abcd");
        assertEquals("你好😀abcd", buffer.toString());
        assertEquals(4, buffer.estimatedTokens());
        assertThrows(RuntimeException.class, () -> buffer.append("字字字"));
        assertThrows(RuntimeException.class, () -> new AssistantOutputBuffer(800).append(" ".repeat(128001)));
    }

    private static List<Message> pair(String user, String assistant) { return List.of(new UserMessage(user), new AssistantMessage(assistant)); }
    private static ChatClient client(FakeModel model, ChatMemory memory) {
        return ChatClient.builder(model).defaultSystem("遵守只读边界").defaultAdvisors(new AssistantMemoryAdvisor(memory)).build();
    }
    private static ChatClient.ChatClientRequestSpec ask(ChatClient client, String id, String user, String evidence) {
        return client.prompt().user(evidence).advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, id)
                .param(AssistantMemoryAdvisor.USER_TEXT, user).param(AssistantMemoryAdvisor.INPUT_LIMIT, 6000)
                .param(AssistantMemoryAdvisor.OUTPUT_LIMIT, 800));
    }
    private static ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private static final class FakeModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        Supplier<Flux<ChatResponse>> stream = () -> Flux.just(response("已查到"), response("两家企业。"));
        @Override public ChatResponse call(Prompt prompt) { prompts.add(prompt); return response("完整回答"); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { prompts.add(prompt); return stream.get(); }
    }
}
