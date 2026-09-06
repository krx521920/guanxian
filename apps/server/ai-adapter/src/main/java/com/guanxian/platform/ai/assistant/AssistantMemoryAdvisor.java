package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.DocumentTextChunker;
import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.List;

/** Memory is added before inference and committed only on a non-empty, bounded successful response. */
final class AssistantMemoryAdvisor implements CallAdvisor, StreamAdvisor {
    static final String USER_TEXT = "guanxian.userText";
    static final String INPUT_LIMIT = "guanxian.inputLimit";
    static final String OUTPUT_LIMIT = "guanxian.outputLimit";
    private final ChatMemory memory;
    AssistantMemoryAdvisor(ChatMemory memory) { this.memory = memory; }
    @Override public String getName() { return "GuanxianCompletedTurnMemory"; }
    @Override public int getOrder() { return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER; }

    private String id(ChatClientRequest request) {
        Object value = request.context().get(ChatMemory.CONVERSATION_ID);
        if (!(value instanceof String key) || key.isBlank()) throw new IllegalArgumentException("conversation id required");
        return key;
    }

    private int limit(ChatClientRequest request, String key, int fallback) {
        Object value = request.context().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private ChatClientRequest prepare(ChatClientRequest request) {
        List<Message> current = request.prompt().getInstructions();
        int used = tokens(current);
        int inputLimit = limit(request, INPUT_LIMIT, 6000);
        if (used > inputLimit) throw new RagLimitException("assistant current context exceeds input budget");
        List<Message> history = fitHistory(memory.get(id(request)), Math.min(2400, inputLimit - used));
        List<Message> combined = new ArrayList<>();
        current.stream().filter(SystemMessage.class::isInstance).forEach(combined::add);
        combined.addAll(history);
        current.stream().filter(message -> !(message instanceof SystemMessage)).forEach(combined::add);
        return request.mutate().prompt(request.prompt().mutate().messages(combined).build()).build();
    }

    static List<Message> fitHistory(List<Message> history, int budget) {
        if (history.isEmpty() || budget <= 0) return List.of();
        List<Message> selected = new ArrayList<>();
        // Recent complete pairs take priority over the old anchor, and are never split.
        int firstPair = history.size() % 2;
        for (int end = history.size(); end - 2 >= firstPair; end -= 2) {
            var pair = history.subList(end - 2, end);
            int size = tokens(pair);
            if (size > budget) break;
            selected.addAll(0, pair);
            budget -= size;
        }
        if (firstPair == 1 && tokens(history.subList(0, 1)) <= budget) selected.add(0, history.getFirst());
        return List.copyOf(selected);
    }

    private static int tokens(List<Message> messages) {
        return messages.stream().mapToInt(message -> DocumentTextChunker.estimateTokens(message.getText()) + 8).sum();
    }

    private void commit(ChatClientRequest request, String text) {
        if (text == null || text.isBlank()) throw new IllegalStateException("empty assistant response");
        if (text.length() > 128000 || DocumentTextChunker.estimateTokens(text) > limit(request, OUTPUT_LIMIT, 800)) {
            throw new RagLimitException("assistant response exceeds memory commit limit");
        }
        String user = request.context().get(USER_TEXT) instanceof String value
                ? value : request.prompt().getUserMessage().getText();
        memory.add(id(request), List.of(new UserMessage(user), new AssistantMessage(text)));
    }

    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var result = chain.nextCall(prepare(request));
        AssistantOutputBuffer.verifyFinish(result.chatResponse());
        String text = result.chatResponse() == null || result.chatResponse().getResult() == null
                ? null : result.chatResponse().getResult().getOutput().getText();
        commit(request, text);
        return result;
    }

    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            var prepared = prepare(request);
            AssistantOutputBuffer text = new AssistantOutputBuffer(limit(request, OUTPUT_LIMIT, 800));
            return chain.nextStream(prepared).doOnNext(response -> {
                if (response.chatResponse() == null || response.chatResponse().getResult() == null) return;
                AssistantOutputBuffer.verifyFinish(response.chatResponse());
                String delta = response.chatResponse().getResult().getOutput().getText();
                if (delta != null) {
                    text.append(delta);
                }
            }).doOnComplete(() -> commit(request, text.toString()));
            // No doFinally/partial aggregation commit: errors and cancellation do not enter history.
        });
    }
}
