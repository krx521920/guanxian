package com.guanxian.platform.ai.rag;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeDocumentParserTest {
    private final RagProperties properties = new RagProperties();
    private final KnowledgeDocumentParser parser = new KnowledgeDocumentParser(properties);

    @Test
    void parsesUtf8TextWithTraceableParserMetadata() {
        var parsed = parser.parse("policy.txt", "text/plain",
                "地下管线运营单位应建立巡检制度。".getBytes(StandardCharsets.UTF_8));

        assertTrue(parsed.text().contains("巡检制度"));
        assertEquals("apache-tika", parsed.parserName());
        assertEquals("3.2.3", parsed.parserVersion());
        assertEquals("text/plain", parsed.mediaType());
    }

    @Test
    void rejectsUnsupportedAndOversizedFiles() {
        assertThrows(KnowledgeDocumentParser.DocumentParseException.class,
                () -> parser.parse("archive.zip", "application/zip", new byte[]{1, 2, 3}));
        properties.setMaxDocumentBytes(1024);
        assertThrows(KnowledgeDocumentParser.DocumentParseException.class,
                () -> parser.parse("large.txt", "text/plain", new byte[1025]));
    }
}
