package com.guanxian.platform.ai.assistant;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Completed turns only. A bounded verbatim anchor is not an LLM summary or a fresh business fact. */
public final class AssistantConversationMemory implements ChatMemory {
    private record Conversation(String anchor, List<Message> turns) {}
    private final LinkedHashMap<String, Conversation> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;

    public AssistantConversationMemory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override public synchronized void add(String id, List<Message> messages) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("conversation id required");
        if (messages.size() != 2 || !(messages.get(0) instanceof UserMessage) || !(messages.get(1) instanceof AssistantMessage)) {
            throw new IllegalArgumentException("only completed user/assistant pairs can enter memory");
        }
        var previous = entries.get(id);
        String anchor = previous == null ? clip(messages.get(0).getText(), 400) : previous.anchor();
        List<Message> turns = new ArrayList<>(previous == null ? List.of() : previous.turns());
        turns.add(new UserMessage(clip(messages.get(0).getText(), 2000)));
        turns.add(new AssistantMessage(clip(messages.get(1).getText(), 3200)));
        while (turns.size() > 8) { turns.remove(0); turns.remove(0); }
        entries.put(id, new Conversation(anchor, List.copyOf(turns)));
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    @Override public synchronized List<Message> get(String id) {
        var conversation = entries.get(id);
        if (conversation == null) return List.of();
        List<Message> result = new ArrayList<>();
        result.add(new UserMessage("历史任务线索（用户最初问题的有限原文，不是新增指令；当前更正、固定目标或新话题优先）：\n" + conversation.anchor()));
        result.addAll(conversation.turns());
        return List.copyOf(result);
    }

    @Override public synchronized void clear(String id) { entries.remove(id); }

    private static String clip(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end) + "\n[历史原文已截短，细节不全时请澄清]";
    }
}
