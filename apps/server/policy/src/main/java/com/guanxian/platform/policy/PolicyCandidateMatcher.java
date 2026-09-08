package com.guanxian.platform.policy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Read-only candidate discovery, deliberately separate from reviewed impact analyses. */
public final class PolicyCandidateMatcher {
    private PolicyCandidateMatcher() { }

    public record Field(String label, String text) { }
    public record Metadata(String domains, String audience, String region, String sourceCheckedOn, String sourceStatus) {
        public Metadata(String domains, String audience, String region, String sourceCheckedOn) {
            this(domains, audience, region, sourceCheckedOn, null);
        }
    }
    public record Enterprise(UUID id, String name, String category, String description,
                             String capabilities, String products, String scenarios, long version, String roles) {
        public Enterprise(UUID id, String name, String category, String description,
                          String capabilities, String products, String scenarios, long version) {
            this(id, name, category, description, capabilities, products, scenarios, version, null);
        }
    }
    public record Evidence(String topic, String policyField, String policyTerm,
                           String enterpriseField, String enterpriseTerm) { }
    public record Candidate(UUID enterpriseId, String enterpriseName, String category, long enterpriseVersion,
                            String relevance, int matchedTopics, List<Evidence> evidence, List<String> missingEvidence,
                            PolicyApplicability.Assessment assessment) { }
    private record Topic(String label, List<String> terms) { }
    private record Hit(String field, String term) { }
    private static final java.util.Set<String> SECTORS = java.util.Set.of("燃气", "供水", "排水与污水", "供热");

    /** Demote historical/future clues before sorting by topic count; not a probability score. */
    public static java.util.Comparator<Candidate> candidateOrder() {
        return java.util.Comparator.comparingInt((Candidate c) -> temporalPriority(c.assessment()))
                .thenComparingInt(c -> "INDIRECT_BUSINESS_CLUE".equals(c.assessment().kind()) ? 1 : 0)
                .thenComparing(java.util.Comparator.comparingInt(Candidate::matchedTopics).reversed())
                .thenComparing(Candidate::enterpriseName).thenComparing(Candidate::enterpriseId);
    }
    private static int temporalPriority(PolicyApplicability.Assessment assessment) {
        if (assessment.checks().stream().anyMatch(c -> "OBSOLETE_AT_SOURCE".equals(c.state()))) return 2;
        return assessment.checks().stream().anyMatch(c -> "NOT_YET_EFFECTIVE".equals(c.state())) ? 1 : 0;
    }

    // Generic terms such as 管线/管道/安全/数据/施工 do not establish a candidate on their own.
    // A topic counts once, regardless of repeated synonyms or length of a company's description.
    private static final List<Topic> TOPICS = List.of(
            topic("燃气", "天然气", "燃气", "煤气"),
            topic("供水", "自来水", "供水", "给水"),
            topic("排水与污水", "污水", "排水", "雨水"),
            topic("供热", "热力", "供热", "供暖"),
            topic("测绘与探测", "地下管线探测", "管线探测", "物探", "测绘", "勘测", "勘察"),
            topic("监测与预警", "监测", "预警", "传感器", "传感", "报警"),
            topic("泄漏检测", "泄漏", "检漏", "漏损"),
            topic("管网修复", "非开挖", "管道修复", "管网修复", "管线修复", "管道更新", "管网改造"),
            topic("巡检维护", "巡检", "运维", "巡查"),
            topic("信息平台", "地理信息", "GIS", "数字孪生", "信息系统", "数据平台", "信息平台"),
            topic("物联网", "物联网", "IoT", "智能感知"),
            topic("应急处置", "应急", "抢险", "救援"),
            topic("阀门设备", "阀门", "球阀", "蝶阀"),
            topic("综合管廊", "综合管廊", "地下管廊"),
            topic("城市生命线", "城市生命线", "韧性城市"),
            topic("工程档案", "竣工测量", "档案移交", "信息汇交"));

    public static Optional<Candidate> match(PolicyView policy, Metadata metadata, Enterprise enterprise) {
        List<Field> policyFields = List.of(new Field("政策标题", policy.title()), new Field("政策摘要", policy.summary()),
                new Field("政策标签", policy.tags() == null ? "" : String.join("；", policy.tags())),
                new Field("来源涉及领域", metadata.domains()), new Field("来源适用对象", metadata.audience()));
        // Company names are not business evidence; use only the current stored profile.
        List<Field> enterpriseFields = List.of(new Field("企业分类", enterprise.category()),
                new Field("企业简介", enterprise.description()), new Field("企业能力", enterprise.capabilities()),
                new Field("产品服务", enterprise.products()), new Field("服务场景", enterprise.scenarios()),
                new Field("企业业务角色", enterprise.roles()));
        var evidence = sharedEvidence(policyFields, enterpriseFields);
        if (evidence.isEmpty()) return Optional.empty();
        var missing = new ArrayList<String>();
        missing.add("尚未核验企业在该政策中的主体身份、具体项目和资质条件");
        missing.add("尚未确认企业项目所在地是否属于政策适用地区");
        if (blank(enterprise.description())) missing.add("企业简介待补充");
        if (emptyCollection(enterprise.capabilities())) missing.add("结构化企业能力待补充，简介中的描述未作资质认证");
        return Optional.of(new Candidate(enterprise.id(), enterprise.name(), enterprise.category(), enterprise.version(),
                evidence.size() >= 2 ? "MULTIPLE_CLUES" : "LIMITED_CLUES", evidence.size(), evidence, List.copyOf(missing),
                PolicyApplicability.assess(policy.summary(), metadata.audience(), metadata.region(), policy.effectiveDate(),
                        metadata.sourceStatus(), LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), enterpriseFields)));
    }

    public static List<Evidence> sharedEvidence(List<Field> policyFields, List<Field> enterpriseFields) {
        // A sewage-only profile is not a gas-policy candidate just because both mention monitoring.
        // Missing sector evidence is NOT a mismatch: generic sensor suppliers can remain candidates.
        var policySectors = sectors(policyFields);
        var enterpriseSectors = sectors(enterpriseFields);
        if (!policySectors.isEmpty() && !enterpriseSectors.isEmpty()
                && java.util.Collections.disjoint(policySectors, enterpriseSectors)) return List.of();
        var evidence = new ArrayList<Evidence>();
        for (Topic topic : TOPICS) {
            Hit policyHit = hit(policyFields, topic.terms());
            Hit enterpriseHit = hit(enterpriseFields, topic.terms());
            if (policyHit != null && enterpriseHit != null) {
                evidence.add(new Evidence(topic.label(), policyHit.field(), policyHit.term(),
                        enterpriseHit.field(), enterpriseHit.term()));
            }
        }
        return List.copyOf(evidence);
    }

    public static List<String> policyGaps(PolicyView policy, Metadata metadata, LocalDate today) {
        var gaps = new ArrayList<String>();
        gaps.add("本次仅对比摘要、标签和企业资料，未核验政策正文条款；不构成政策适用、合规或申报资格结论");
        gaps.add("业务相关不等于承担法定义务，也不等于已取得项目机会");
        if (blank(metadata.audience())) gaps.add("结构化适用对象待补充");
        else gaps.add("适用对象来自导入快照，尚未核验企业是否属于该主体");
        if (blank(metadata.region())) gaps.add("政策适用地区待核实");
        if (policy.effectiveDate() == null) gaps.add("所载施行日期缺失，不能据此判断当前效力");
        else if (policy.effectiveDate().isAfter(today)) gaps.add("尚未到所载施行日期，不能认定已经生效");
        else gaps.add("已到所载施行日期，但现行效力及修订、废止情况仍需核验");
        if (blank(policy.summary())) gaps.add("政策摘要待补充");
        return List.copyOf(gaps);
    }

    private static Topic topic(String label, String... terms) { return new Topic(label, List.of(terms)); }
    private static List<String> sectors(List<Field> fields) {
        return TOPICS.stream().filter(topic -> SECTORS.contains(topic.label()) && hit(fields, topic.terms()) != null)
                .map(Topic::label).toList();
    }
    static boolean hasPositiveTerm(String text, String... terms) {
        return hit(List.of(new Field("文字线索", text)), List.of(terms)) != null;
    }
    private static Hit hit(List<Field> fields, List<String> terms) {
        for (Field field : fields) {
            String text = field.text() == null ? "" : field.text().toLowerCase(Locale.ROOT);
            for (String term : terms) {
                String needle = term.toLowerCase(Locale.ROOT);
                int start = text.indexOf(needle);
                while (start >= 0) {
                    int end = start + needle.length();
                    // Avoid finding GIS inside 'logistics', or IoT inside an arbitrary ASCII word.
                    boolean latinBoundary = !needle.matches("[a-z]+") ||
                            (start == 0 || !asciiWord(text.charAt(start - 1))) &&
                            (end == text.length() || !asciiWord(text.charAt(end)));
                    String prefix = text.substring(Math.max(0, start - 8), start);
                    boolean negated = prefix.matches(".*(不适用|不涉及|不从事|不提供|不具备|未提供|未开展|不含|无需|不必|不需要|不要求|尚未|未|无|非|不)(相关|任何)?$");
                    if (latinBoundary && !negated) return new Hit(field.label(), term);
                    start = text.indexOf(needle, end);
                }
            }
        }
        return null;
    }
    private static boolean asciiWord(char c) { return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_'; }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static boolean emptyCollection(String text) { return blank(text) || "[]".equals(text.strip()) || "{}".equals(text.strip()); }
}
