package com.guanxian.platform.ai.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedChatMemoryRepositoryTest {
    @Test
    void evictsTheLeastRecentlyUsedConversation() {
        var repository = new BoundedChatMemoryRepository(2);
        repository.saveAll("first", List.of());
        repository.saveAll("second", List.of());

        repository.findByConversationId("first");
        repository.saveAll("third", List.of());

        assertTrue(repository.findConversationIds().contains("first"));
        assertTrue(repository.findConversationIds().contains("third"));
        assertFalse(repository.findConversationIds().contains("second"));
    }

    @Test
    void returnsDefensiveMessageLists() {
        var repository = new BoundedChatMemoryRepository(1);
        repository.saveAll("conversation", List.of());

        assertEquals(List.of(), repository.findByConversationId("conversation"));
    }
}
