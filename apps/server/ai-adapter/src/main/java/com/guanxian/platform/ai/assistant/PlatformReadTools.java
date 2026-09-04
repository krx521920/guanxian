package com.guanxian.platform.ai.assistant;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PlatformReadTools implements AssistantToolProvider {
    static final String PAGE_PATH = "pagePath";
    static final String PAGE_TITLE = "pageTitle";

    private static final Map<String, String> PAGE_GUIDES = Map.ofEntries(
            Map.entry("/association", "协会工作台：查看协会概览、待办和运营指标。"),
            Map.entry("/enterprise", "企业工作台：查看企业资料、能力和待办。"),
            Map.entry("/members", "会员企业：查询、查看、新增或批量导入会员企业；写操作仍由页面权限控制。"),
            Map.entry("/policies", "政策标准：管理政策资料、执行带出处的知识问答和影响分析。"),
            Map.entry("/ecosystem/overview", "生态全景：查看协会生态的角色、能力和供需分布。"),
            Map.entry("/ecosystem", "产品与需求：管理企业供给能力和公开需求。"),
            Map.entry("/matching", "生态匹配：基于已审核的公开需求和在架能力生成、确认匹配记录。"),
            Map.entry("/collaborations", "协作事项：跟踪合作事项、参与方、阶段和归档状态。"),
            Map.entry("/attachments", "资料附件：查看和下载当前身份有权访问的文件。"),
            Map.entry("/federation", "友好协会：查看协会间的协作关系和共享范围。"),
            Map.entry("/operations", "审计与账号：查看审计记录并执行有权限的账号管理操作。")
    );

    @Tool(
            name = "current_page_help",
            description = "读取当前管线智联页面的用途和操作提示。仅用于页面导航与使用说明，不执行任何写操作。")
    public String currentPageHelp(ToolContext toolContext) {
        String path = contextValue(toolContext, PAGE_PATH, "/");
        String title = contextValue(toolContext, PAGE_TITLE, "当前页面");
        String route = PAGE_GUIDES.keySet().stream()
                .filter(candidate -> path.equals(candidate) || path.startsWith(candidate + "/"))
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .findFirst()
                .orElse(null);
        String guide = route == null
                ? "当前页面暂无专门说明，请以页面可见按钮和权限提示为准。"
                : PAGE_GUIDES.get(route);
        return "页面：" + title + "（" + path + "）。" + guide
                + " 本工具不会提升权限，也不会代替用户提交、修改或删除数据。";
    }

    @Override
    public Object toolObject() {
        return this;
    }

    private static String contextValue(ToolContext toolContext, String key, String fallback) {
        if (toolContext == null || toolContext.getContext() == null) return fallback;
        Object value = toolContext.getContext().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
