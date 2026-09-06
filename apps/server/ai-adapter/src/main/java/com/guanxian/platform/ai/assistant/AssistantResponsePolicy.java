package com.guanxian.platform.ai.assistant;

import java.util.Locale;
import java.util.List;

/** A deterministic response contract, not a claim that the model has executed a plan. */
public final class AssistantResponsePolicy {
    public enum Detail { AUTO, BRIEF, STANDARD, DETAILED }
    public record Strategy(Detail detail, boolean multiStep, String instructions) {}

    private AssistantResponsePolicy() {}

    public static Strategy select(String question, String requestedDetail) {
        Detail detail;
        try { detail = requestedDetail == null ? Detail.AUTO : Detail.valueOf(requestedDetail.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid response detail"); }
        String text = question == null ? "" : question;
        int brief = lastMarker(text, List.of("简短回答", "简单说", "只要结论", "一句话", "不要展开", "简洁一点"));
        int detailed = lastMarker(text, List.of("详细说明", "详细解释", "展开讲", "完整方案", "逐步解释", "详细一点"));
        // The latest explicit instruction in this turn takes precedence over the UI preference.
        if (brief >= 0 || detailed >= 0) detail = brief > detailed ? Detail.BRIEF : Detail.DETAILED;
        boolean multiStep = List.of("方案", "分步骤", "拆解", "计划", "验收", "先", "然后", "分别", "对比", "比较")
                .stream().filter(text::contains).count() >= 2 || text.contains("完整方案");
        if (detail == Detail.AUTO) detail = multiStep ? Detail.DETAILED : Detail.STANDARD;
        String instructions = switch (detail) {
            case BRIEF -> "简洁：先给直接结论，通常一至三句话；必要风险或权限缺口不能省略。不要强行分章节。";
            case DETAILED -> "详细：先给结论，再给分组依据、关键明细与下一步；避免重复开场和空泛总结。不得为凑长度编造信息。";
            default -> "标准：结论优先，按需列三至五个关键点；一个事实或简单追问直接回答，不套固定长模板。";
        };
        if (multiStep) instructions += "\n多步任务：按依赖顺序组织，区分可查询的事实、待补信息、建议步骤与验收条件。没有执行证据的步骤只能叫建议或待办，不能标为已完成。先回答有证据的部分，关键缺口只提一个具体澄清问题。";
        instructions += "\n追问优先承接最近明确指代；若存在多个可能对象，不猜测，先澄清。当前用户的明确更正优先于历史内容；换话题后不要强行沿用旧目标。";
        return new Strategy(detail, multiStep, instructions);
    }

    private static int lastMarker(String text, List<String> markers) {
        return markers.stream().mapToInt(text::lastIndexOf).max().orElse(-1);
    }
}
