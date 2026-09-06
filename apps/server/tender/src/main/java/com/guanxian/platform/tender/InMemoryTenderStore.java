package com.guanxian.platform.tender;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryTenderStore implements TenderStore {
    private static final UUID DEMO_ASSOCIATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000106");

    private final ConcurrentMap<UUID, TenderView> tenders = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<TenderPushView>> pushes = new ConcurrentHashMap<>();

    InMemoryTenderStore() {
        this(false);
    }

    @Autowired
    InMemoryTenderStore(
            @Value("${guanxian.business.seed-demo-data:${guanxian.member.seed-demo-data:false}}")
            boolean seedDemoData) {
        if (!seedDemoData) {
            return;
        }
        seed();
    }

    @Override
    public List<TenderView> findAll(
            String query, String category, String region, String status, int offset, int limit) {
        return tenders.values().stream()
                .filter(tender -> matches(query, tender))
                .filter(tender -> category == null || category.equals(tender.category()))
                .filter(tender -> region == null || region.equals(tender.region()))
                .filter(tender -> status == null || status.equals(tender.status()))
                .sorted(Comparator.comparing(TenderView::publishDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TenderView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countAll(String query, String category, String region, String status) {
        return tenders.values().stream()
                .filter(tender -> matches(query, tender))
                .filter(tender -> category == null || category.equals(tender.category()))
                .filter(tender -> region == null || region.equals(tender.region()))
                .filter(tender -> status == null || status.equals(tender.status()))
                .count();
    }

    @Override
    public Optional<TenderView> findById(UUID id) {
        return Optional.ofNullable(tenders.get(id));
    }

    @Override
    public synchronized TenderView insert(
            UUID associationId, TenderUpsertRequest request, ActorScope actor) {
        UUID id = UUID.randomUUID();
        TenderView value = fromRequest(id, associationId, request, 0, Instant.now());
        tenders.put(id, value);
        return value;
    }

    @Override
    public synchronized Optional<TenderView> update(
            UUID id, long expectedVersion, TenderUpsertRequest request, ActorScope actor) {
        TenderView old = tenders.get(id);
        if (old == null || old.version() != expectedVersion) {
            return Optional.empty();
        }
        TenderView updated = fromRequest(id, old.associationId(), request, old.version() + 1, old.createdAt());
        tenders.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized TenderPushView upsertPush(
            UUID tenderId, String tenderTitle, UUID enterpriseId, String enterpriseName, ActorScope actor) {
        List<TenderPushView> existing = pushes.computeIfAbsent(tenderId, ignored -> new CopyOnWriteArrayList<>());
        for (TenderPushView push : existing) {
            if (push.enterpriseId().equals(enterpriseId)) {
                return push;
            }
        }
        TenderPushView created = new TenderPushView(
                UUID.randomUUID().toString(), tenderId, tenderTitle, enterpriseId, enterpriseName,
                actor.subject(), Instant.now(), "PUSHED", 0);
        existing.add(created);
        return created;
    }

    @Override
    public List<TenderPushView> findPushes(UUID tenderId) {
        return pushes.getOrDefault(tenderId, List.of()).stream()
                .sorted(Comparator.comparing(TenderPushView::pushedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TenderPushView::id))
                .toList();
    }

    @Override
    public List<UUID> listPushedEnterpriseIds(UUID tenderId) {
        return pushes.getOrDefault(tenderId, List.of()).stream()
                .map(TenderPushView::enterpriseId)
                .toList();
    }

    @Override
    public List<TenderView> findRelevant(List<String> enterpriseKeywords, String region, int offset, int limit) {
        return tenders.values().stream()
                .filter(tender -> relevant(tender, enterpriseKeywords))
                .filter(tender -> region == null || region.equals(tender.region()))
                .sorted(Comparator.comparing(TenderView::publishDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TenderView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countRelevant(List<String> enterpriseKeywords, String region) {
        return tenders.values().stream()
                .filter(tender -> relevant(tender, enterpriseKeywords))
                .filter(tender -> region == null || region.equals(tender.region()))
                .count();
    }

    private static TenderView fromRequest(
            UUID id, UUID associationId, TenderUpsertRequest request, long version, Instant createdAt) {
        Instant now = Instant.now();
        return new TenderView(
                id.toString(), associationId, request.title().trim(), request.purchaser().trim(),
                clean(request.agency()), clean(request.region()), request.category().trim(),
                list(request.keywords()), request.budget(), request.publishDate(), request.deadline(),
                clean(request.source()), clean(request.sourceUrl()),
                status(request.status()), version, createdAt, now);
    }

    private static boolean relevant(TenderView tender, List<String> enterpriseKeywords) {
        if (!"ACTIVE".equals(tender.status()) || enterpriseKeywords == null || enterpriseKeywords.isEmpty()) {
            return false;
        }
        for (String keyword : enterpriseKeywords) {
            String needle = clean(keyword);
            if (needle == null) {
                continue;
            }
            String lower = needle.toLowerCase(Locale.ROOT);
            if (tender.category() != null && tender.category().toLowerCase(Locale.ROOT).contains(lower)) {
                return true;
            }
            for (String tenderKeyword : tender.keywords()) {
                if (tenderKeyword != null && tenderKeyword.toLowerCase(Locale.ROOT).contains(lower)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(String query, TenderView tender) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return String.join(" ",
                nonNull(tender.title()), nonNull(tender.purchaser()), nonNull(tender.agency()),
                nonNull(tender.region()), nonNull(tender.category()), nonNull(tender.source()),
                String.join(" ", tender.keywords()))
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String status(String value) {
        return value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private void seed() {
        seed("30000000-0000-0000-0000-000000000001",
                "北京市东城区2026年雨污合流管线改造工程招标公告",
                "北京市东城区城市管理委员会", "北京中建政研咨询有限公司", "东城区", "排水管网",
                List.of("雨污合流", "管线改造", "排水"), 8_500_000L,
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 8, 6), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000002",
                "北京市西城区老旧小区燃气管网更新改造工程公开招标公告",
                "北京市西城区城市管理委员会", "北京国际招标有限公司", "西城区", "燃气管网",
                List.of("燃气", "老旧小区", "更新改造"), 12_000_000L,
                LocalDate.of(2026, 7, 8), LocalDate.of(2026, 8, 10), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000003",
                "北京市朝阳区2026年供水管网隐患治理项目招标公告",
                "北京市自来水集团有限责任公司", null, "朝阳区", "供水管网",
                List.of("供水", "隐患治理", "管网"), 9_800_000L,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 12), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000004",
                "北京市海淀区地下管线普查与探测项目招标公告",
                "北京市海淀区城市管理委员会", "中招国际招标有限公司", "海淀区", "管线探测",
                List.of("管线普查", "管线探测", "测量"), 4_600_000L,
                LocalDate.of(2026, 7, 12), LocalDate.of(2026, 8, 14), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000005",
                "北京市丰台区供热一次管网改造工程招标公告",
                "北京市热力集团有限责任公司", "北京国际贸易有限公司", "丰台区", "热力管网改造",
                List.of("供热", "一次管网", "改造"), 15_600_000L,
                LocalDate.of(2026, 7, 15), LocalDate.of(2026, 8, 17), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000006",
                "北京市石景山区排水管线非开挖修复项目招标公告",
                "北京市石景山区水务局", "北京京园招标有限公司", "石景山区", "非开挖修复",
                List.of("排水", "非开挖修复", "管道修复"), 7_200_000L,
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 8, 20), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000007",
                "北京市门头沟区山区供水管线建设工程招标公告",
                "北京市门头沟区水务局", "北京华诚永信工程管理有限公司", "门头沟区", "供水管网",
                List.of("供水", "山区供水", "建设"), 13_500_000L,
                LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 22), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000008",
                "北京市房山区燃气管道泄漏检测服务招标公告",
                "北京市房山区城市管理委员会", null, "房山区", "燃气管网",
                List.of("燃气", "泄漏检测", "安全"), 3_800_000L,
                LocalDate.of(2026, 7, 22), LocalDate.of(2026, 8, 24), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000009",
                "北京市通州区综合管廊主体工程材料采购招标公告",
                "北京城市副中心投资建设集团有限公司", "北京科技园拍卖招标有限公司", "通州区", "材料设备",
                List.of("综合管廊", "材料采购", "设备"), 22_000_000L,
                LocalDate.of(2026, 7, 25), LocalDate.of(2026, 8, 27), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000010",
                "北京市顺义区热力管网智慧监测平台建设项目招标公告",
                "北京市顺义区城市管理委员会", "北京国泰建中管理咨询有限公司", "顺义区", "智慧监测",
                List.of("热力", "智慧监测", "数字化"), 6_800_000L,
                LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 29), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000011",
                "北京市昌平区老旧小区供水管网改造工程招标公告",
                "北京市昌平区水务局", "北京筑标建设工程咨询有限公司", "昌平区", "供水管网",
                List.of("供水", "老旧小区", "改造"), 18_900_000L,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 2), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000012",
                "北京市昌平区回天地区燃气管网更新工程招标公告",
                "北京市昌平区城市管理委员会", "北京中建精诚工程咨询有限公司", "昌平区", "燃气管网",
                List.of("燃气", "回天地区", "更新"), 26_500_000L,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 4), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000013",
                "北京市昌平区未来科学城综合管廊运维招标公告",
                "北京未来科学城发展集团有限公司", null, "昌平区", "综合管廊",
                List.of("综合管廊", "运维", "地下空间"), 30_000_000L,
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 7), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000014",
                "北京市大兴区排水管网清淤与检测项目招标公告",
                "北京市大兴区水务局", "北京东方华太工程咨询有限公司", "大兴区", "排水管网",
                List.of("排水", "清淤", "检测"), 5_400_000L,
                LocalDate.of(2026, 8, 7), LocalDate.of(2026, 9, 9), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000015",
                "北京市怀柔区供热管网及换热站改造工程招标公告",
                "北京市怀柔区城市管理委员会", "北京北咨工程咨询有限公司", "怀柔区", "热力管网改造",
                List.of("供热", "换热站", "改造"), 11_200_000L,
                LocalDate.of(2026, 8, 9), LocalDate.of(2026, 9, 11), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000016",
                "北京市平谷区农村供水管网提升改造工程招标公告",
                "北京市平谷区水务局", "北京奇泰桥工程技术咨询有限公司", "平谷区", "供水管网",
                List.of("供水", "农村", "提升改造"), 8_700_000L,
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 9, 14), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000017",
                "北京市密云区燃气管网安全评估与改造项目招标公告",
                "北京市密云区城市管理委员会", "北京国际工程咨询有限公司", "密云区", "燃气管网",
                List.of("燃气", "安全评估", "改造"), 9_300_000L,
                LocalDate.of(2026, 8, 13), LocalDate.of(2026, 9, 16), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000018",
                "北京市延庆区世园区域综合管线探测项目招标公告",
                "北京市延庆区城市管理委员会", null, "延庆区", "管线探测",
                List.of("管线探测", "综合管线", "测量"), 4_100_000L,
                LocalDate.of(2026, 8, 15), LocalDate.of(2026, 9, 18), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000019",
                "北京市朝阳区CBD区域智慧管网监测终端采购招标公告",
                "北京商务中心区管理委员会", "北京中建源建筑工程管理有限公司", "朝阳区", "材料设备",
                List.of("智慧管网", "监测终端", "采购"), 15_800_000L,
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 9, 21), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000020",
                "北京市海淀区中关村软件园地下管线非开挖修复工程招标公告",
                "北京中关村软件园发展有限责任公司", "北京北咨招标有限公司", "海淀区", "非开挖修复",
                List.of("非开挖修复", "地下管线", "修复"), 12_600_000L,
                LocalDate.of(2026, 8, 19), LocalDate.of(2026, 9, 23), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000021",
                "北京市丰台区丽泽商务区综合管廊设备采购招标公告",
                "北京丽泽金融商务区控股集团有限公司", "北京国际招标有限公司", "丰台区", "材料设备",
                List.of("综合管廊", "设备采购", "机电"), 19_500_000L,
                LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 25), "中国招标投标公共服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000022",
                "北京市房山区周口店供热管网改造工程招标公告",
                "北京市房山区城市管理委员会", "北京华源国际工程咨询有限公司", "房山区", "热力管网改造",
                List.of("供热", "周口店", "改造"), 10_400_000L,
                LocalDate.of(2026, 8, 23), LocalDate.of(2026, 9, 28), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000023",
                "北京市大兴区生物医药基地排水管线工程招标公告",
                "北京生物医药产业基地发展有限公司", null, "大兴区", "排水管网",
                List.of("排水", "生物医药基地", "管线工程"), 7_600_000L,
                LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 30), "北京市公共资源交易服务平台", null, "ACTIVE");
        seed("30000000-0000-0000-0000-000000000024",
                "北京市顺义区临空经济区燃气管网迁改工程招标公告",
                "北京顺义临空经济区管理委员会", "北京招信天诚招标代理有限公司", "顺义区", "燃气管网",
                List.of("燃气", "临空经济区", "迁改"), 17_500_000L,
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 9, 30), "北京市公共资源交易服务平台", null, "ACTIVE");
    }

    private void seed(String id, String title, String purchaser, String agency, String region, String category,
                      List<String> keywords, long budget, LocalDate publishDate, LocalDate deadline,
                      String source, String sourceUrl, String status) {
        UUID uuid = UUID.fromString(id);
        Instant now = Instant.now();
        tenders.put(uuid, new TenderView(
                id, DEMO_ASSOCIATION_ID, title, purchaser, agency, region, category, keywords, budget,
                publishDate, deadline, source, sourceUrl, status, 0, now, now));
    }
}
