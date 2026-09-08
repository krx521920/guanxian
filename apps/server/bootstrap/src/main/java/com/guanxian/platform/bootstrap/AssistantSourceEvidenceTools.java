package com.guanxian.platform.bootstrap;

import com.guanxian.platform.ai.assistant.*;
import com.guanxian.platform.bootstrap.SourceDirectoryService.Entry;
import com.guanxian.platform.bootstrap.SourceDirectoryService.Kind;
import com.guanxian.platform.shared.error.ForbiddenException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Stored public-source evidence only: no live crawler, raw payload, account data or business writes. */
@Component
@Order(-10)
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "postgres", matchIfMissing = true)
public class AssistantSourceEvidenceTools implements AssistantToolProvider, AssistantLocalQueryProvider {
    private static final int LIMIT = 5;
    private static final String BOUNDARY = "已入库公开资料的查询快照，并非全网实时检索；历史公告不代表仍可投标，候选不等于中标，活动不等于平台确认的合作。";
    private final SourceDirectoryService directory;
    public AssistantSourceEvidenceTools(SourceDirectoryService directory) { this.directory = directory; }
    public enum Period { ALL, LAST_30_DAYS, LAST_YEAR, CUSTOM }
    public record SearchQuery(
            @ToolParam(description = "精确的连续关键词，如企业名、项目或业务词；不要放整句问题", required = false) String keyword,
            @ToolParam(description = "ALL不限日期；LAST_30_DAYS近30天；LAST_YEAR近一年；CUSTOM自定发布日期区间", required = false) Period period,
            @ToolParam(description = "CUSTOM时可选开始发布日期，YYYY-MM-DD", required = false) String fromDate,
            @ToolParam(description = "CUSTOM时可选结束发布日期，YYYY-MM-DD", required = false) String toDate,
            @ToolParam(description = "从0开始的页码，每页最多5条；下一页保留同样条件", required = false) Integer page) { }

    @Tool(name = "search_tender_evidence", description = "查询当前账号可见的已入库外部招标、采购、中标候选和历史结果资料。不是会员发布的合作需求，不是实时全网搜索；企业角色只按来源证据表述，不能推断仍可投标或已具资质。")
    public AssistantBusinessResults.Result searchTenders(@ToolParam(description = "查询条件") SearchQuery query, ToolContext context) {
        return search(Kind.TENDER, query, null, context);
    }
    @Tool(name = "search_activity_evidence", description = "查询当前账号可见的已入库企业活动与公开动态，区分计划、报道和企业自述；不是平台协作记录，不代表已经确认合作。")
    public AssistantBusinessResults.Result searchActivities(@ToolParam(description = "查询条件") SearchQuery query, ToolContext context) {
        return search(Kind.ACTIVITY, query, null, context);
    }
    @Tool(name = "read_source_evidence", description = "按本轮查询得到的资料ID重新读取一条招采或活动证据，重新检查权限；不能传入网址或自行猜测ID，不访问外部网页。")
    public AssistantBusinessResults.Result readEvidence(@ToolParam(description = "TENDER或ACTIVITY") String kind,
            @ToolParam(description = "从实际查询结果中获得的资料UUID") UUID recordId, ToolContext context) {
        Kind type = recordId == null ? null : "TENDER".equals(kind) ? Kind.TENDER : "ACTIVITY".equals(kind) ? Kind.ACTIVITY : null;
        return search(type, new SearchQuery("", Period.ALL, null, null, 0), recordId, context);
    }
    @Override public Object toolObject() { return this; }

    private record Criteria(String keyword, LocalDate from, LocalDate to, int page, String period) { }
    private static Criteria criteria(SearchQuery query, LocalDate today) {
        if (query == null) throw new IllegalArgumentException("query required");
        String keyword = query.keyword() == null ? "" : query.keyword().strip();
        int page = query.page() == null ? 0 : query.page();
        if (keyword.length() > 200 || page < 0 || page > 10000) throw new IllegalArgumentException("query out of range");
        Period period = query.period() == null ? Period.ALL : query.period();
        LocalDate from = null, to = null;
        if (period == Period.LAST_YEAR) { from = today.minusYears(1); to = today; }
        if (period == Period.LAST_30_DAYS) { from = today.minusDays(29); to = today; }
        if (period == Period.CUSTOM) {
            from = date(query.fromDate()); to = date(query.toDate());
            if (from == null && to == null || from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("invalid dates");
        } else if (query.fromDate() != null && !query.fromDate().isBlank() || query.toDate() != null && !query.toDate().isBlank()) {
            throw new IllegalArgumentException("dates require CUSTOM");
        }
        return new Criteria(keyword, from, to, page, period.name());
    }
    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("ISO date required");
        return LocalDate.parse(value);
    }
    private AssistantBusinessResults.Result search(Kind kind, SearchQuery query, UUID recordId, ToolContext context) {
        AssistantToolBudget.consume(context);
        Object value = context == null ? null : context.getContext().get(AssistantAccessContext.TOOL_CONTEXT_KEY);
        if (!(value instanceof AssistantAccessContext access)) throw new IllegalStateException("Authenticated assistant context required");
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("数据性质", BOUNDARY);
        String status = "OK"; long total = 0; List<AssistantBusinessResults.Item> items = List.of();
        try {
            if (!access.hasAuthority("MEMBER_READ")) throw new ForbiddenException("SOURCE_READ_REQUIRED", "No source access");
            if (kind != Kind.TENDER && kind != Kind.ACTIVITY) throw new IllegalArgumentException("invalid kind");
            Criteria c = criteria(query, LocalDate.now(ZoneId.of("Asia/Shanghai")));
            filters.put("关键词", c.keyword().isEmpty() ? "未筛选" : c.keyword());
            filters.put("发布日期", c.from() == null && c.to() == null ? "不限日期（含日期未登记）"
                    : (c.from() == null ? "不限开始" : c.from()) + " 至 " + (c.to() == null ? "不限结束" : c.to()) + "；含边界，排除日期缺失/无效记录");
            filters.put("分页", "第 " + (c.page() + 1) + " 页，每页最多 " + LIMIT + " 条；按所载发布日期降序");
            if (recordId != null) filters.put("资料ID", recordId.toString());
            var result = directory.search(kind, c.keyword(), c.page(), LIMIT, access.actor(), c.from(), c.to(), recordId);
            total = result.total();
            items = result.items().stream().map(entry -> item(kind, entry)).toList();
            if (recordId != null && items.isEmpty()) status = "UNAVAILABLE";
        } catch (ForbiddenException error) { status = "FORBIDDEN"; }
        catch (IllegalArgumentException | java.time.DateTimeException error) { status = "INVALID"; }
        catch (RuntimeException error) { status = "FAILED"; }
        if (!"OK".equals(status)) { total = 0; items = List.of(); }
        var receipt = AssistantBusinessResults.Result.create(kind == Kind.ACTIVITY ? "ACTIVITY_EVIDENCE" : "TENDER_EVIDENCE",
                status, kind == Kind.ACTIVITY ? "企业活动与公开动态查询" : "外部招采与历史结果查询", access.actor().associationId(), filters, total, items,
                access.actor().isSystemAdmin() && access.actor().associationId() == null);
        AssistantBusinessResults.record(context, receipt);
        return receipt;
    }
    private static String field(Entry entry, String name) { return entry.fields().path(name).asText(""); }
    private static String first(String... values) { return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(""); }
    private static AssistantBusinessResults.Item item(Kind kind, Entry entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("发布日期", field(entry, "发布日期"));
        fields.put("来源记录状态", first(field(entry, "记录状态"), field(entry, "公告类型"), "未登记"));
        fields.put("关联企业及角色", field(entry, "关联企业及角色"));
        fields.put("内容摘要", first(field(entry, "证据摘要"), field(entry, "采购内容")));
        fields.put("项目地区", field(entry, "项目地区"));
        fields.put("金额及口径", java.util.stream.Stream.of(first(field(entry, "金额及口径"), field(entry, "预算或最高限价")),
                field(entry, "分标段信息"), field(entry, "披露份额")).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining("；")));
        fields.put(kind == Kind.TENDER ? "所载截止或开标时间" : "所载活动时间", field(entry, kind == Kind.TENDER ? "截止或开标时间" : "活动时间"));
        fields.put("所载要求", field(entry, "关键要求"));
        fields.put("后续结果", field(entry, "后续结果"));
        fields.put("核验边界", BOUNDARY + field(entry, "核验边界"));
        fields.replaceAll((key, text) -> AssistantBusinessResults.text(text));
        var evidence = entry.evidence();
        String raw = first(evidence == null ? null : evidence.sourceUrl(), field(entry, "原文链接"));
        List<String> links = new ArrayList<>();
        if (evidence != null) links.addAll(evidence.supportingUrls());
        if (entry.correction() != null && entry.correction().path("evidenceUrls").isArray())
            entry.correction().path("evidenceUrls").forEach(url -> { if (url.isTextual()) links.add(url.asText()); });
        links.add(field(entry, "更正公告"));
        // Preserve known imported compound notice/correction values as two links, never arbitrary prose URLs.
        var compound = java.util.regex.Pattern.compile("^(https?://[^\\s；;]+)[；;]更正[：:](https?://[^\\s；;]+)$").matcher(raw);
        if (compound.matches()) { raw = compound.group(1); links.add(compound.group(2)); }
        String primary = safeUrl(raw);
        var source = new AssistantBusinessResults.SourceReference(kind.name(), shortText(entry.sourceId(), 100),
                shortText(evidence == null ? "" : evidence.recordId(), 100),
                shortText(first(evidence == null ? null : evidence.checkedOn(), field(entry, "核验日期")), 40), primary,
                links.stream().map(AssistantSourceEvidenceTools::safeUrl).filter(Objects::nonNull).filter(url -> !url.equals(primary)).distinct().limit(3).toList());
        return new AssistantBusinessResults.Item(entry.id(), AssistantBusinessResults.text(first(evidence == null ? null : evidence.title(), entry.title())),
                "SOURCE", fields, List.of(), source);
    }
    private static String shortText(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    static String safeUrl(String value) {
        if (value == null || value.length() > 2048 || !value.matches("(?i)^https?://.*") || value.matches("(?s).*[\\s\\\\\\p{Cntrl}；;].*")) return null;
        if (java.util.regex.Pattern.compile("https?://", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value).results().count() != 1) return null;
        try { var uri = URI.create(value); return uri.getHost() != null && uri.getUserInfo() == null ? value : null; }
        catch (IllegalArgumentException error) { return null; }
    }

    private static String intentText(String message) { return message == null ? "" : message.replaceAll("[“\"「][^”\"」]*[”\"」]", ""); }
    static boolean sourceIntent(String message) { return intentText(message).matches("(?s).*(招标|投标|招采|中标|标书|活动|公开动态).*?"); }
    @Override public Optional<LocalQueryResult> answer(LocalQueryRequest request) {
        if (!sourceIntent(request.message())) return Optional.empty();
        String text = request.message().strip();
        String intent = intentText(text);
        if (intent.matches("(?s).*(删除|修改|导入|报名|申请|帮我投|发布|发通知|推荐哪|符合资格).*"))
            return Optional.of(new LocalQueryResult("当前是本地只读资料查询，不能修改、报名投标或判断投标资格。可查询已入库招采/活动资料并核对来源。", "LOCAL_BUSINESS_QUERY"));
        boolean activity = intent.matches("(?s).*(活动|动态).*"), tender = intent.matches("(?s).*(招标|投标|招采|中标|标书).*" );
        Period period = intent.matches("(?s).*(近一年|最近一年|一年内|过去一年).*" ) ? Period.LAST_YEAR
                : intent.matches("(?s).*(最近|近期|近30天|近一个月).*" ) ? Period.LAST_30_DAYS : Period.ALL;
        var quoted = java.util.regex.Pattern.compile("[“\"「]([^”\"」]+)[”\"」]").matcher(text);
        String keyword = quoted.find() ? quoted.group(1).strip() : "";
        if (keyword.length() > 200) return Optional.of(new LocalQueryResult("关键词不能超过200字，请缩短后重试；尚未执行查询。", "LOCAL_BUSINESS_QUERY"));
        if (quoted.find()) return Optional.of(new LocalQueryResult("本地查询一次只支持一个连续关键词，请分别查询；不会忽略第二个条件。复杂多条件问题可使用模型模式。", "LOCAL_BUSINESS_QUERY"));
        // Do not silently broaden named-company, arbitrary date or multi-condition questions.
        String rest = text.replaceAll("[“\"「][^”\"」]*[”\"」]", "").replaceAll("近一年|最近一年|一年内|过去一年|近30天|近一个月|最近|近期", "")
                .replaceAll("请帮我|帮我|查一下|查一查|查询|查找|搜索|列出|看看|看一下|有哪些|有什么|多少|当前|现在|目前|全部协会|所有协会|全协会|全部|所有|会员企业|会员单位|企业|公开|动态|活动|资料|记录|招投标|招标|投标|招采|中标|标书|相关|已经|已入库|入库|的|和|与|及|一批|请|查", "")
                .replaceAll("[\\p{P}\\s]+", "");
        if (!rest.isEmpty()) return Optional.of(new LocalQueryResult("本地查询尚不能可靠拆解这些筛选条件。请将企业名或关键词放在引号中，例如：查询近一年“管线监测”招标资料。支持近一年、近30天或不限日期；模型模式可使用自定发布日期区间。", "LOCAL_BUSINESS_QUERY"));
        var journal = new AssistantBusinessResults();
        var context = new ToolContext(Map.of(AssistantAccessContext.TOOL_CONTEXT_KEY, request.access(), AssistantBusinessResults.CONTEXT_KEY, journal));
        SearchQuery query = new SearchQuery(keyword, period, null, null, 0);
        if (tender) searchTenders(query, context);
        if (activity) searchActivities(query, context);
        StringBuilder answer = new StringBuilder("已按当前权限查询平台已入库的公开来源资料（未调用大模型）。\n");
        for (var result : journal.snapshot()) {
            answer.append(result.label()).append("：");
            if (!"OK".equals(result.status())) answer.append("查询未成功（").append(result.status()).append("），不能当作没有数据。\n");
            else { answer.append("共 ").append(result.total()).append(" 条，本次展示 ").append(result.items().size()).append(" 条。\n");
                result.items().forEach(item -> answer.append("- ").append(item.name()).append("（来源 ").append(item.source().sourceId()).append("）\n")); }
        }
        answer.append(BOUNDARY).append("筛选条件、角色、核验日期和原文链接见下方查询凭据；更多记录可在对应资料目录分页查看。具体投标资格需另行核对。");
        return Optional.of(new LocalQueryResult(answer.toString(), "LOCAL_BUSINESS_QUERY", journal.snapshot()));
    }
}
