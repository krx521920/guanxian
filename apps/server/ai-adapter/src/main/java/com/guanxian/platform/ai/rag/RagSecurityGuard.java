package com.guanxian.platform.ai.rag;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class RagSecurityGuard {
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior)\\s+instructions?"),
            Pattern.compile("(?i)(reveal|show|print|leak)\\s+(the\\s+)?(system|developer)\\s+prompt"),
            Pattern.compile("忽略(以上|之前|先前).{0,12}(指令|要求|规则)"),
            Pattern.compile("(泄露|显示|输出).{0,10}(系统提示词|开发者指令|隐藏指令)"),
            Pattern.compile("(?i)(jailbreak|developer\\s+mode|bypass\\s+safety)")
    );
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*\\S+"),
            Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")
    );

    private final RagProperties properties;

    public RagSecurityGuard(RagProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    public void validateQuestion(String question) {
        if (question == null || question.isBlank()) throw new UnsafePromptException("question is required");
        if (question.length() > properties.getMaxQuestionChars()) throw new UnsafePromptException("question exceeds the configured length limit");
        if (containsAny(question, INJECTION_PATTERNS)) throw new UnsafePromptException("question contains a prompt-injection pattern");
        if (containsAny(question, SECRET_PATTERNS)) throw new UnsafePromptException("question appears to contain sensitive credentials or identity data");
    }

    public void validateKnowledgeDocument(String title, String sourceUrl, String content) {
        validateRequiredField(title, "knowledge document title");
        validateOptionalField(sourceUrl, "knowledge source URL");
        validateRequiredField(content, "knowledge document content");
    }

    public boolean safeRetrievedDocument(String title, String sourceUrl, String content) {
        return safeRequiredField(title)
                && safeOptionalField(sourceUrl)
                && safeRequiredField(content);
    }

    public boolean safeRetrievedContent(String content) {
        return safeRequiredField(content);
    }

    private void validateRequiredField(String value, String field) {
        if (value == null || value.isBlank()) throw new UnsafePromptException(field + " is required");
        validateOptionalField(value, field);
    }

    private void validateOptionalField(String value, String field) {
        if (value == null || value.isBlank()) return;
        if (containsAny(value, INJECTION_PATTERNS)) {
            throw new UnsafePromptException(field + " contains a prompt-injection pattern");
        }
        if (containsAny(value, SECRET_PATTERNS)) {
            throw new UnsafePromptException(field + " appears to contain sensitive credentials or identity data");
        }
    }

    private boolean safeRequiredField(String value) {
        return value != null && !value.isBlank() && safeOptionalField(value);
    }

    private boolean safeOptionalField(String value) {
        return value == null || value.isBlank()
                || (!containsAny(value, INJECTION_PATTERNS) && !containsAny(value, SECRET_PATTERNS));
    }

    private boolean containsAny(String value, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    public static class UnsafePromptException extends IllegalArgumentException {
        public UnsafePromptException(String message) { super(message); }
    }
}
