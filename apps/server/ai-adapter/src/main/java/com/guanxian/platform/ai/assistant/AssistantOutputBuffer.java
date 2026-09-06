package com.guanxian.platform.ai.assistant;

import com.guanxian.platform.ai.rag.PolicyRagService.RagLimitException;
import org.springframework.ai.chat.model.ChatResponse;

/** Linear-time output accounting, including a Unicode surrogate pair split across deltas. */
final class AssistantOutputBuffer {
    private final StringBuilder value = new StringBuilder();
    private final int maxTokens;
    private int han, other;
    private char pendingHigh;
    AssistantOutputBuffer(int maxTokens) { this.maxTokens = maxTokens; }
    void append(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if ((long) value.length() + delta.length() > 128000) throw new RagLimitException("assistant character limit exceeded");
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (pendingHigh != 0) {
                if (Character.isLowSurrogate(ch)) {
                    count(Character.toCodePoint(pendingHigh, ch)); pendingHigh = 0; continue;
                }
                count(pendingHigh); pendingHigh = 0;
            }
            if (Character.isHighSurrogate(ch)) pendingHigh = ch;
            else count(ch);
        }
        if (estimatedTokens() > maxTokens) throw new RagLimitException("assistant output token limit exceeded");
        value.append(delta);
    }
    private void count(int ch) {
        if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) han++;
        else if (!Character.isWhitespace(ch)) other++;
    }
    int estimatedTokens() { return han + Math.max(1, (other + (pendingHigh == 0 ? 0 : 1) + 3) / 4); }
    @Override public String toString() { return value.toString(); }

    static void verifyFinish(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) return;
        String finish = response.getResult().getMetadata().getFinishReason();
        if ("length".equalsIgnoreCase(finish)) throw new RagLimitException("model output was truncated");
        if ("content_filter".equalsIgnoreCase(finish)) throw new IllegalStateException("model output was filtered");
    }
}
