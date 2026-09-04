package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.AssistantAccessContext;
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
import java.util.UUID;

/**
 * Read-only business tools. Every query reuses the same scoped services as the HTTP controllers,
 * and authority checks use a server-created snapshot carried outside model-visible arguments.
 */
@Component
public class AssistantBusinessQueryTools implements AssistantToolProvider {
    private static final int RESULT_LIMIT = 10;

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
}
