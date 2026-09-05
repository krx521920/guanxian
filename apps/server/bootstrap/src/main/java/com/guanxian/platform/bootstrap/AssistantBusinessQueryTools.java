package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.AssistantAccessContext;
import com.guanxian.platform.ai.assistant.AssistantLocalQueryProvider;
import com.guanxian.platform.ai.assistant.AssistantToolProvider;
import com.guanxian.platform.collaboration.CollaborationService;
import com.guanxian.platform.collaboration.CollaborationView;
import com.guanxian.platform.ecosystem.DemandView;
import com.guanxian.platform.ecosystem.EcosystemCatalogService;
import com.guanxian.platform.ecosystem.EcosystemMatchService;
import com.guanxian.platform.ecosystem.OfferingView;
import com.guanxian.platform.ecosystem.PersistedMatchView;
import com.guanxian.platform.member.api.MemberProfile;
import com.guanxian.platform.member.internal.MemberService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only business tools. Every query reuses the same scoped services as the HTTP controllers,
 * and authority checks use a server-created snapshot carried outside model-visible arguments.
 */
@Component
public class AssistantBusinessQueryTools implements AssistantToolProvider, AssistantLocalQueryProvider {
    private static final int RESULT_LIMIT = 10;
    private static final String LOCAL_MODE = "LOCAL_BUSINESS_QUERY";
    private static final List<String> KNOWLEDGE_MARKERS = List.of(
            "政策", "标准", "规范", "条款", "合规", "资料库", "文档", "依据", "要求", "规定");

    private final MemberService memberService;
    private final EcosystemCatalogService catalogService;
    private final EcosystemMatchService matchService;
    private final CollaborationService collaborationService;

    public AssistantBusinessQueryTools(
            MemberService memberService,
            EcosystemCatalogService catalogService,
            EcosystemMatchService matchService,
            CollaborationService collaborationService) {
        this.memberService = memberService;
        this.catalogService = catalogService;
        this.matchService = matchService;
        this.collaborationService = collaborationService;
    }

    @Tool(
            name = "search_member_enterprises",
            description = "按名称、行业、能力、产品或服务查询当前账号有权查看的会员企业。只返回非敏感摘要，不返回联系人、电话、邮箱或统一信用代码。")
    public ToolResult searchMemberEnterprises(
            @ToolParam(description = "查询关键词；不需要筛选时留空", required = false) String keyword,
            ToolContext toolContext) {
        AssistantAccessContext access = access(toolContext);
        if (!access.hasAuthority("MEMBER_READ")) return denied("MEMBER_READ");
        List<MemberProfile> visible = memberService.findAll(normalizeQuery(keyword), null, false, access.actor());
        List<MemberSummary> items = visible.stream().limit(RESULT_LIMIT).map(AssistantBusinessQueryTools::member).toList();
        return ok(visible.size(), items);
    }

    @Tool(
            name = "search_product_services",
            description = "查询当前账号有权查看的企业产品与服务能力，可按名称、企业或场景关键词筛选。")
    public ToolResult searchProductServices(
            @ToolParam(description = "查询关键词；不需要筛选时留空", required = false) String keyword,
            ToolContext toolContext) {
        AssistantAccessContext access = access(toolContext);
        if (!access.hasAuthority("MEMBER_READ")) return denied("MEMBER_READ");
        var page = catalogService.offerings(access.actor(), normalizeQuery(keyword), false, 0, RESULT_LIMIT);
        return ok(page.total(), page.items().stream().map(AssistantBusinessQueryTools::offering).toList());
    }

    @Tool(
            name = "search_business_demands",
            description = "查询当前账号有权查看的合作需求，可按需求标题、企业、场景或所需能力关键词筛选。")
    public ToolResult searchBusinessDemands(
            @ToolParam(description = "查询关键词；不需要筛选时留空", required = false) String keyword,
            ToolContext toolContext) {
        AssistantAccessContext access = access(toolContext);
        if (!access.hasAuthority("MEMBER_READ")) return denied("MEMBER_READ");
        var page = catalogService.demands(access.actor(), normalizeQuery(keyword), false, 0, RESULT_LIMIT);
        return ok(page.total(), page.items().stream().map(AssistantBusinessQueryTools::demand).toList());
    }

    @Tool(
            name = "list_ecosystem_matches",
            description = "查询当前账号有权查看的生态匹配记录和状态。可按精确英文状态筛选，不提供状态时返回最近记录。")
    public ToolResult listEcosystemMatches(
            @ToolParam(description = "可选英文状态，例如 ASSOCIATION_RECOMMENDED、BOTH_CONFIRMED、NEGOTIATING、ARCHIVED", required = false)
            String state,
            ToolContext toolContext) {
        AssistantAccessContext access = access(toolContext);
        if (!access.hasAuthority("MATCH_REQUEST")) return denied("MATCH_REQUEST");
        var page = matchService.persistedReadOnly(access.actor(), 0, RESULT_LIMIT, normalizeState(state));
        return ok(page.total(), page.items().stream().map(AssistantBusinessQueryTools::match).toList());
    }

    @Tool(
            name = "search_collaboration_items",
            description = "查询当前账号有权查看的协作事项、阶段、优先级、下一步和截止日期。")
    public ToolResult searchCollaborationItems(
            @ToolParam(description = "标题或事项关键词；不需要筛选时留空", required = false) String keyword,
            @ToolParam(description = "可选英文阶段，例如 OPEN、IN_PROGRESS、COMPLETED", required = false) String stage,
            ToolContext toolContext) {
        AssistantAccessContext access = access(toolContext);
        if (!access.hasAuthority("COLLABORATION_READ")) return denied("COLLABORATION_READ");
        var page = collaborationService.page(
                access.actor(), normalizeQuery(keyword), normalizeState(stage), false, 0, RESULT_LIMIT);
        return ok(page.total(), page.items().stream().map(AssistantBusinessQueryTools::collaboration).toList());
    }

    @Override
    public Object toolObject() {
        return this;
    }

    /**
     * Handles only high-confidence read-only intents. Ambiguous questions deliberately fall back to
     * the grounded knowledge path instead of pretending that a deterministic router is a model.
     */
    @Override
    public Optional<LocalQueryResult> answer(LocalQueryRequest request) {
        QueryKind kind = queryKind(request.message(), request.pagePath());
        if (kind == null) return Optional.empty();

        ToolContext context = new ToolContext(Map.of(
                AssistantAccessContext.TOOL_CONTEXT_KEY, request.access()));
        String keyword = extractKeyword(request.message(), kind);
        String answer = switch (kind) {
            case MEMBERS -> formatMembers(searchMemberEnterprises(keyword, context), keyword);
            case OFFERINGS -> formatOfferings(searchProductServices(keyword, context), keyword);
            case DEMANDS -> formatDemands(searchBusinessDemands(keyword, context), keyword);
            case MATCHES -> formatMatches(listEcosystemMatches(extractMatchState(request.message()), context));
            case COLLABORATIONS -> formatCollaborations(searchCollaborationItems(
                    keyword, extractCollaborationStage(request.message()), context), keyword);
        };
        return Optional.of(new LocalQueryResult(answer, LOCAL_MODE));
    }

    private static AssistantAccessContext access(ToolContext toolContext) {
        Object value = toolContext == null || toolContext.getContext() == null
                ? null
                : toolContext.getContext().get(AssistantAccessContext.TOOL_CONTEXT_KEY);
        if (!(value instanceof AssistantAccessContext access)) {
            throw new IllegalStateException("assistant authorization context is unavailable");
        }
        return access;
    }

    private static ToolResult ok(long total, List<?> items) {
        String message = total > RESULT_LIMIT
                ? "仅返回当前权限范围内前 " + RESULT_LIMIT + " 条，共 " + total + " 条。"
                : "共 " + total + " 条。";
        return new ToolResult("OK", message, total, items);
    }

    private static ToolResult denied(String authority) {
        return new ToolResult(
                "FORBIDDEN",
                "当前账号缺少 " + authority + " 权限，未执行查询。",
                0,
                List.of());
    }

    private static String normalizeQuery(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100);
    }

    private static String normalizeState(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z_]{1,40}")) {
            throw new IllegalArgumentException("tool state filter is invalid");
        }
        return normalized;
    }

    private static QueryKind queryKind(String value, String pagePath) {
        String message = value == null ? "" : value.strip();
        if (message.isEmpty() || containsAny(message, KNOWLEDGE_MARKERS)) return null;

        if (containsAny(message, List.of("协作事项", "合作事项", "协作进展", "待办事项"))) {
            return QueryKind.COLLABORATIONS;
        }
        if (containsAny(message, List.of("生态匹配", "匹配记录", "匹配进展", "供需匹配"))) {
            return QueryKind.MATCHES;
        }
        if (containsAny(message, List.of("合作需求", "业务需求", "开放需求", "需求列表", "采购需求"))) {
            return QueryKind.DEMANDS;
        }
        if (containsAny(message, List.of(
                "产品与服务", "产品服务", "服务能力", "供给能力", "产品列表", "有哪些产品", "提供什么服务"))) {
            return QueryKind.OFFERINGS;
        }
        if (containsAny(message, List.of(
                "会员企业", "会员单位", "企业名单", "企业列表", "多少家企业", "有哪些企业"))) {
            return QueryKind.MEMBERS;
        }

        if (!isGenericListQuestion(message)) return null;
        String path = pagePath == null ? "" : pagePath;
        if (path.startsWith("/members")) return QueryKind.MEMBERS;
        if (path.startsWith("/matching")) return QueryKind.MATCHES;
        if (path.startsWith("/collaborations")) return QueryKind.COLLABORATIONS;
        return path.startsWith("/ecosystem") ? QueryKind.OFFERINGS : null;
    }

    private static boolean isGenericListQuestion(String value) {
        return containsAny(value, List.of("查询", "查找", "搜索", "列出", "有哪些", "多少", "当前", "现在", "进展", "状态"));
    }

    private static boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }

    private static String extractKeyword(String value, QueryKind kind) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        List<String> noise = switch (kind) {
            case MEMBERS -> List.of(
                    "会员企业", "会员单位", "企业名单", "企业列表", "企业", "会员", "能力", "多少家", "哪些", "有");
            case OFFERINGS -> List.of(
                    "产品与服务", "产品服务", "服务能力", "供给能力", "产品列表", "产品", "服务", "能力", "哪些", "有", "提供什么");
            case DEMANDS -> List.of(
                    "合作需求", "业务需求", "开放需求", "需求列表", "采购需求", "需求", "哪些", "有");
            case MATCHES -> List.of(
                    "生态匹配", "匹配记录", "匹配进展", "供需匹配", "匹配", "记录", "进展", "哪些", "有");
            case COLLABORATIONS -> List.of(
                    "协作事项", "合作事项", "协作进展", "待办事项", "协作", "事项", "待办", "进展", "哪些", "有");
        };
        for (String token : List.of("请帮我", "帮我", "请", "查询", "查找", "搜索", "列出", "看看", "当前", "现在", "目前", "情况", "状态", "一下")) {
            normalized = normalized.replace(token, "");
        }
        for (String token : noise) normalized = normalized.replace(token, "");
        normalized = normalized.replaceAll("[\\p{P}\\p{S}\\s]+", "").strip();
        return normalized.length() < 2 ? null : normalizeQuery(normalized);
    }

    private static String extractMatchState(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        for (String state : List.of(
                "ASSOCIATION_RECOMMENDED", "BOTH_CONFIRMED", "NEGOTIATING", "ARCHIVED", "CLOSED")) {
            if (normalized.contains(state)) return state;
        }
        if (normalized.contains("协商") || normalized.contains("洽谈")) return "NEGOTIATING";
        if (normalized.contains("双方确认")) return "BOTH_CONFIRMED";
        if (normalized.contains("已归档")) return "ARCHIVED";
        if (normalized.contains("已关闭")) return "CLOSED";
        return null;
    }

    private static String extractCollaborationStage(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        for (String stage : List.of("OPEN", "IN_PROGRESS", "COMPLETED", "ARCHIVED", "CLOSED")) {
            if (normalized.contains(stage)) return stage;
        }
        if (normalized.contains("进行中")) return "IN_PROGRESS";
        if (normalized.contains("已完成")) return "COMPLETED";
        if (normalized.contains("已归档")) return "ARCHIVED";
        if (normalized.contains("已关闭")) return "CLOSED";
        if (normalized.contains("待处理") || normalized.contains("未开始")) return "OPEN";
        return null;
    }

    private static String formatMembers(ToolResult result, String keyword) {
        if (!"OK".equals(result.status())) return result.message();
        List<MemberSummary> items = result.items().stream().map(MemberSummary.class::cast).toList();
        if (items.isEmpty()) return emptyMessage("会员企业", keyword);
        StringBuilder text = heading("会员企业", result, keyword);
        for (int index = 0; index < items.size(); index++) {
            MemberSummary item = items.get(index);
            text.append(index + 1).append(". ").append(item.name())
                    .append("｜").append(display(item.category())).append("｜").append(display(item.status()))
                    .append('\n')
                    .append("   能力：").append(join(item.capabilities()))
                    .append("；产品/服务：").append(joinCombined(item.products(), item.services())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String formatOfferings(ToolResult result, String keyword) {
        if (!"OK".equals(result.status())) return result.message();
        List<OfferingSummary> items = result.items().stream().map(OfferingSummary.class::cast).toList();
        if (items.isEmpty()) return emptyMessage("产品与服务", keyword);
        StringBuilder text = heading("产品与服务", result, keyword);
        for (int index = 0; index < items.size(); index++) {
            OfferingSummary item = items.get(index);
            text.append(index + 1).append(". ").append(item.name())
                    .append("｜企业：").append(display(item.enterpriseName()))
                    .append("｜状态：").append(display(item.status())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String formatDemands(ToolResult result, String keyword) {
        if (!"OK".equals(result.status())) return result.message();
        List<DemandSummary> items = result.items().stream().map(DemandSummary.class::cast).toList();
        if (items.isEmpty()) return emptyMessage("业务需求", keyword);
        StringBuilder text = heading("业务需求", result, keyword);
        for (int index = 0; index < items.size(); index++) {
            DemandSummary item = items.get(index);
            text.append(index + 1).append(". ").append(item.title())
                    .append("｜企业：").append(display(item.enterpriseName()))
                    .append("｜状态：").append(display(item.status()))
                    .append("｜所需能力：").append(join(item.requiredCapabilities())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String formatMatches(ToolResult result) {
        if (!"OK".equals(result.status())) return result.message();
        List<MatchSummary> items = result.items().stream().map(MatchSummary.class::cast).toList();
        if (items.isEmpty()) return "当前权限范围内没有查询到生态匹配记录。";
        StringBuilder text = heading("生态匹配记录", result, null);
        for (int index = 0; index < items.size(); index++) {
            MatchSummary item = items.get(index);
            text.append(index + 1).append(". ").append(display(item.demandTitle()))
                    .append("｜需求方：").append(display(item.demandCompany()))
                    .append("｜供给方：").append(display(item.supplierCompany()))
                    .append("｜状态：").append(display(item.state()))
                    .append("｜评分：").append(item.score() == null ? "暂无" : item.score()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static String formatCollaborations(ToolResult result, String keyword) {
        if (!"OK".equals(result.status())) return result.message();
        List<CollaborationSummary> items = result.items().stream().map(CollaborationSummary.class::cast).toList();
        if (items.isEmpty()) return emptyMessage("协作事项", keyword);
        StringBuilder text = heading("协作事项", result, keyword);
        for (int index = 0; index < items.size(); index++) {
            CollaborationSummary item = items.get(index);
            text.append(index + 1).append(". ").append(item.title())
                    .append("｜阶段：").append(display(item.stage()))
                    .append("｜优先级：").append(display(item.priority()))
                    .append("｜进度：").append(item.progress()).append('%')
                    .append("｜下一步：").append(display(item.nextAction())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static StringBuilder heading(String label, ToolResult result, String keyword) {
        String filter = keyword == null ? "" : "（关键词：“" + keyword + "”）";
        String unit = "会员企业".equals(label) ? " 家" : " 条";
        return new StringBuilder("当前权限范围内共 ")
                .append(result.total()).append(unit).append(label).append(filter)
                .append(result.total() > result.items().size() ? "，显示前 " + result.items().size() + " 条" : "")
                .append("：\n");
    }

    private static String emptyMessage(String label, String keyword) {
        return keyword == null
                ? "当前权限范围内没有查询到" + label + "。"
                : "当前权限范围内没有查询到与“" + keyword + "”相关的" + label + "。";
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "暂无" : String.join("、", values);
    }

    private static String joinCombined(List<String> first, List<String> second) {
        List<String> values = new java.util.ArrayList<>();
        if (first != null) values.addAll(first);
        if (second != null) values.addAll(second);
        return join(values.stream().distinct().toList());
    }

    private static String display(Object value) {
        return value == null || value.toString().isBlank() ? "暂无" : value.toString();
    }

    private static MemberSummary member(MemberProfile value) {
        return new MemberSummary(
                value.id(), value.name(), value.category(), value.capabilities(), value.products(),
                value.services(), value.status(), value.updatedAt());
    }

    private static OfferingSummary offering(OfferingView value) {
        return new OfferingSummary(
                value.id(), value.enterpriseName(), value.name(), value.kind(), clip(value.description()),
                value.scenarios(), value.qualifications(), value.status(), value.updatedAt());
    }

    private static DemandSummary demand(DemandView value) {
        return new DemandSummary(
                value.id(), value.enterpriseName(), value.title(), clip(value.description()),
                value.scenarios(), value.requiredCapabilities(), value.budgetMin(), value.budgetMax(),
                value.responseDeadline(), value.status(), value.updatedAt());
    }

    private static MatchSummary match(PersistedMatchView value) {
        return new MatchSummary(
                value.id(), value.demandCompany(), value.demandTitle(), value.supplierCompany(),
                clip(value.solution()), value.score(), value.reasons(), value.state(), value.updatedAt());
    }

    private static CollaborationSummary collaboration(CollaborationView value) {
        return new CollaborationSummary(
                value.id(), value.title(), value.stage(), value.priority(), clip(value.nextAction()),
                value.dueDate(), value.progress(), value.updatedAt());
    }

    private static String clip(String value) {
        if (value == null || value.length() <= 300) return value;
        return value.substring(0, 299) + "…";
    }

    public record ToolResult(String status, String message, long total, List<?> items) {
        public ToolResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record MemberSummary(
            UUID id,
            String name,
            String category,
            List<String> capabilities,
            List<String> products,
            List<String> services,
            String status,
            Instant updatedAt) {
    }

    public record OfferingSummary(
            UUID id,
            String enterpriseName,
            String name,
            String kind,
            String description,
            List<String> scenarios,
            List<String> qualifications,
            String status,
            Instant updatedAt) {
    }

    public record DemandSummary(
            UUID id,
            String enterpriseName,
            String title,
            String description,
            List<String> scenarios,
            List<String> requiredCapabilities,
            BigDecimal budgetMin,
            BigDecimal budgetMax,
            Instant responseDeadline,
            String status,
            Instant updatedAt) {
    }

    public record MatchSummary(
            UUID id,
            String demandCompany,
            String demandTitle,
            String supplierCompany,
            String solution,
            Integer score,
            List<String> reasons,
            String state,
            Instant updatedAt) {
    }

    public record CollaborationSummary(
            UUID id,
            String title,
            String stage,
            String priority,
            String nextAction,
            LocalDate dueDate,
            int progress,
            Instant updatedAt) {
    }

    private enum QueryKind {
        MEMBERS,
        OFFERINGS,
        DEMANDS,
        MATCHES,
        COLLABORATIONS
    }
}
