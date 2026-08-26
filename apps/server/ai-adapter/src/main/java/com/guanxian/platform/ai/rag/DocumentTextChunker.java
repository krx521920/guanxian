package com.guanxian.platform.ai.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class DocumentTextChunker {
    private static final String BOUNDARIES = "\n。！？；.!?;";

    private final int chunkSize;
    private final int overlap;

    public DocumentTextChunker(RagProperties properties) {
        properties.validate();
        this.chunkSize = properties.getChunkSizeChars();
        this.overlap = properties.getChunkOverlapChars();
    }

    public List<TextChunk> split(String rawText) {
        if (rawText == null || rawText.isBlank()) return List.of();
        String text = rawText.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(text.length(), start + chunkSize);
            int end = chooseBoundary(text, start, hardEnd);
            String content = text.substring(start, end).strip();
            if (!content.isEmpty()) {
                chunks.add(new TextChunk(index++, content, sha256(content), estimateTokens(content)));
            }
            if (end >= text.length()) break;
            int nextStart = Math.max(start + 1, end - overlap);
            while (nextStart < end && Character.isWhitespace(text.charAt(nextStart))) nextStart++;
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private int chooseBoundary(String text, int start, int hardEnd) {
        if (hardEnd == text.length()) return hardEnd;
        int minimum = start + (int) (chunkSize * 0.6);
        for (int cursor = hardEnd - 1; cursor >= minimum; cursor--) {
            if (BOUNDARIES.indexOf(text.charAt(cursor)) >= 0) return cursor + 1;
        }
        return hardEnd;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int han = 0;
        int other = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) han++;
            else if (!Character.isWhitespace(codePoint)) other++;
            offset += Character.charCount(codePoint);
        }
        return han + Math.max(1, (other + 3) / 4);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record TextChunk(int index, String content, String contentHash, int tokenCount) {
        public TextChunk {
            if (index < 0 || content == null || content.isBlank() || contentHash == null || contentHash.length() != 64 || tokenCount < 1) {
                throw new IllegalArgumentException("text chunk is invalid");
            }
        }
    }
}
