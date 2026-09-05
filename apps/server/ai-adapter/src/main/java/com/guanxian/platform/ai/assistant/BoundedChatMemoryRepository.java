package com.guanxian.platform.ai.assistant;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * In-memory conversation storage with a hard conversation-count limit.
 * Messages remain process-local and are discarded on restart.
 */
public final class BoundedChatMemoryRepository implements ChatMemoryRepository {
    private final int maxConversations;
    private final LinkedHashMap<String, List<Message>> conversations = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedChatMemoryRepository(int maxConversations) {
        if (maxConversations < 1) throw new IllegalArgumentException("max conversations must be positive");
        this.maxConversations = maxConversations;
    }

    @Override
    public synchronized List<String> findConversationIds() {
        return List.copyOf(conversations.keySet());
    }

    @Override
    public synchronized List<Message> findByConversationId(String conversationId) {
        List<Message> messages = conversations.get(conversationId);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversation id is required");
        }
        if (messages == null) throw new IllegalArgumentException("messages are required");
        conversations.put(conversationId, List.copyOf(messages));
        while (conversations.size() > maxConversations) {
            String eldest = conversations.entrySet().iterator().next().getKey();
            conversations.remove(eldest);
        }
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        conversations.remove(conversationId);
    }
}
