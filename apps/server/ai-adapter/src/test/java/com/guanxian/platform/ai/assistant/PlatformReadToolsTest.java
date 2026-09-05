package com.guanxian.platform.ai.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformReadToolsTest {
    @Test
    void pageHelpUsesOnlyServerSuppliedToolContext() {
        String result = new PlatformReadTools().currentPageHelp(new ToolContext(Map.of(
                PlatformReadTools.PAGE_TITLE, "会员企业",
                PlatformReadTools.PAGE_PATH, "/members/new")));

        assertTrue(result.contains("会员企业"));
        assertTrue(result.contains("批量导入"));
        assertTrue(result.contains("不会提升权限"));
    }
}
