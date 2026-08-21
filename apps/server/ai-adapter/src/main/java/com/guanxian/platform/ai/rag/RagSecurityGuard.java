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

    public boolean safeRetrievedContent(String content) {
        return content != null && !content.isBlank()
                && !containsAny(content, INJECTION_PATTERNS)
                && !containsAny(content, SECRET_PATTERNS);
    }

    private boolean containsAny(String value, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    public static class UnsafePromptException extends IllegalArgumentException {
        public UnsafePromptException(String message) { super(message); }
    }
}
