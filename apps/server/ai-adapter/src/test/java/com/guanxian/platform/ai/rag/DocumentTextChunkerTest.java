package com.guanxian.platform.ai.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentTextChunkerTest {
    @Test
    void splitsChineseTextOnBoundariesWithStableHashesAndOverlap() {
        RagProperties properties = new RagProperties();
        properties.setChunkSizeChars(200);
        properties.setChunkOverlapChars(30);
        DocumentTextChunker chunker = new DocumentTextChunker(properties);
        String text = ("地下管线安全运行需要落实巡检、监测、隐患整改和应急处置。"
                + "协会会员单位应当保存处置记录并按照要求报告。").repeat(10);

        var chunks = chunker.split(text);

        assertTrue(chunks.size() > 1);
        assertEquals(0, chunks.getFirst().index());
        assertEquals(64, chunks.getFirst().contentHash().length());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() > 0 && chunk.content().length() <= 200));
        assertEquals(chunks.getFirst().contentHash(), DocumentTextChunker.sha256(chunks.getFirst().content()));
    }

    @Test
    void blankDocumentProducesNoChunks() {
        RagProperties properties = new RagProperties();
        assertTrue(new DocumentTextChunker(properties).split("  ").isEmpty());
    }
}
