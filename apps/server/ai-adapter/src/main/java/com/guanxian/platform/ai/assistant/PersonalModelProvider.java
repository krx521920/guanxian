package com.guanxian.platform.ai.assistant;

import java.util.List;

/** Server-owned destinations only: a personal key must never be sent to an arbitrary URL. */
public enum PersonalModelProvider {
    DOUBAO("豆包 · 火山方舟", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "填写方舟模型 ID 或 ep- 接入点 ID", "https://www.volcengine.com/docs/82379/1330626"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "填写控制台可用的模型 ID", "https://api-docs.deepseek.com/"),
    KIMI("Kimi · 月之暗面", "https://api.moonshot.cn/v1/chat/completions", "填写 Kimi 控制台可用的模型 ID", "https://platform.kimi.com/docs/get-api-key"),
    QWEN("千问 · 阿里云百炼（北京）", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "例如 qwen-plus；须使用北京地域 API Key", "https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope");

    private final String label;
    private final String endpoint;
    private final String modelHint;
    private final String documentationUrl;

    PersonalModelProvider(String label, String endpoint, String modelHint, String documentationUrl) {
        this.label = label;
        this.endpoint = endpoint;
        this.modelHint = modelHint;
        this.documentationUrl = documentationUrl;
    }

    public String endpoint() { return endpoint; }
    public static List<Option> options() {
        return java.util.Arrays.stream(values()).map(value -> new Option(
                value.name(), value.label, value.endpoint, value.modelHint, value.documentationUrl)).toList();
    }
    public record Option(String id, String label, String endpoint, String modelHint, String documentationUrl) { }
}
