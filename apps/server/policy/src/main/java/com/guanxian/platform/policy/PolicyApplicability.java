package com.guanxian.platform.policy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Describes checks and uncertainty, not a legal eligibility decision. */
public final class PolicyApplicability {
    private PolicyApplicability() { }
    public record Check(String dimension, String state, String explanation) { }
    public record Assessment(String kind, String applicability, List<Check> checks) { }
    private record Role(String label, List<String> terms) { }
    private static final List<Role> ROLES = List.of(
            new Role("运营经营", List.of("运营单位", "运营企业", "经营企业", "经营者", "运营商", "运营服务", "运营管理")),
            new Role("建设业主", List.of("建设单位", "项目业主", "项目法人")),
            new Role("施工", List.of("施工单位", "施工企业", "施工服务", "承包商", "施工总承包")),
            new Role("设计勘察", List.of("设计单位", "设计企业", "勘察单位", "工程设计", "勘察设计")),
            new Role("设备制造", List.of("生产企业", "制造企业", "生产者", "制造商", "设备制造", "生产制造", "设备生产")),
            new Role("检测监测服务", List.of("检测机构", "检测单位", "监测单位", "检测服务", "监测服务")));

    public static Assessment assess(String summary, String audience, String region, LocalDate effective,
                                    String sourceStatus, LocalDate today) {
        return assess(summary, audience, region, effective, sourceStatus, today, List.of());
    }

    public static Assessment assess(String summary, String audience, String region, LocalDate effective,
                                    String sourceStatus, LocalDate today, List<PolicyCandidateMatcher.Field> enterpriseFields) {
        String text = summary == null ? "" : summary;
        // A recommendation in one sentence must not cancel an obligation in a different sentence.
        boolean nonMandatory = nonMandatory(text);
        boolean obligation = java.util.Arrays.stream(text.split("[。；;\\n]|但是|但"))
                .anyMatch(clause -> !nonMandatory(clause) && positive(clause, "应当", "必须", "不得", "强制性"));
        boolean opportunity = java.util.Arrays.stream(text.split("[。；;，,\\n]|但是|但"))
                .anyMatch(clause -> !contains(clause, "不支持", "不予补贴", "不提供补贴", "不安排采购", "无补贴", "取消补贴")
                        && positive(clause, "机会", "鼓励", "支持", "推进", "建设", "改造", "采购", "补贴"));
        boolean governmentOnly = contains(audience, "政府", "主管部门", "行政机关")
                && !positive(audience, "企业", "经营者", "运营", "施工", "生产者", "建设单位", "设计单位", "检测机构");
        var audienceRoles = roles(audience);
        String profile = String.join("；", enterpriseFields.stream().map(f -> f.text() == null ? "" : f.text()).toList());
        var companyRoles = roles(profile);
        var sharedRoles = audienceRoles.stream().filter(companyRoles::contains).toList();
        boolean differentRole = !audienceRoles.isEmpty() && !companyRoles.isEmpty() && sharedRoles.isEmpty();
        String kind = governmentOnly ? "INDIRECT_OPPORTUNITY" : differentRole ? "INDIRECT_BUSINESS_CLUE"
                : obligation && opportunity ? "MIXED_CLUES"
                : obligation ? "COMPLIANCE_CLUE" : opportunity ? "BUSINESS_OPPORTUNITY" : "RELATED_TOPIC";
        var checks = new ArrayList<Check>();
        checks.add(new Check("适用对象", governmentOnly ? "GOVERNMENT_SCOPE"
                : differentRole ? "DIFFERENT_ROLE_CLUE" : !sharedRoles.isEmpty() ? "ROLE_CLUE_FOUND" : "NEEDS_VERIFICATION",
                blank(audience) ? "缺少适用对象，不能仅凭行业词判断企业承担义务"
                        : governmentOnly ? "所载对象是政府或行政部门；企业线索仅作间接业务参考"
                        : differentRole ? "所载对象：" + audience + "；企业资料仅发现“" + String.join("、", companyRoles)
                            + "”角色，未发现对象所需的“" + String.join("、", audienceRoles) + "”角色。保留间接业务线索，不据此认定企业承担该义务或必然不适用"
                        : !sharedRoles.isEmpty() ? "所载对象：" + audience + "；企业资料发现“" + String.join("、", sharedRoles)
                            + "”角色线索，但不代表资质已核验，仍须核对实际项目职责"
                        : "所载对象：" + audience + "；仍须核对企业的实际主体身份和项目职责"));
        checks.add(new Check("适用地区", "NEEDS_PROJECT_LOCATION",
                blank(region) ? "适用地区未登记，不能把公司名称或注册地址当作项目所在地"
                        : "所载地区：" + region + "；仍须核对具体项目所在地，不能仅按注册地址认定适用"));
        // A snapshot saying 未废止 / 部分条款废止 is not a whole-policy repeal.
        String state = sourceStatus != null && sourceStatus.strip().matches("^(已)?(废止|失效|作废)([（(：:].*)?$") ? "OBSOLETE_AT_SOURCE"
                : effective == null ? "DATE_UNKNOWN" : effective.isAfter(today) ? "NOT_YET_EFFECTIVE" : "RECHECK_REQUIRED";
        checks.add(new Check("效力与时间", state, switch (state) {
            case "OBSOLETE_AT_SOURCE" -> "来源快照标记已失效，不作为当前适用依据";
            case "DATE_UNKNOWN" -> "缺少施行日期，现行效力待核实";
            case "NOT_YET_EFFECTIVE" -> "尚未到所载施行日期 " + effective + "，仅作提前了解";
            default -> "已到所载施行日期，但仍须核验修订、废止及过渡期";
        }));
        checks.add(new Check("义务与机会", "CLUE_ONLY", nonMandatory && !obligation ? "材料注明非强制或推荐性，不能转成强制合规要求"
                : nonMandatory ? "材料同时含推荐性与义务措辞，必须逐条核验，不能互相覆盖"
                : "仅按材料措辞区分义务线索与业务机会；最终以原文、适用条件和人工核验为准"));
        return new Assessment(kind, "UNVERIFIED", List.copyOf(checks));
    }
    private static List<String> roles(String text) {
        return ROLES.stream().filter(role -> positive(text, role.terms().toArray(String[]::new))).map(Role::label).toList();
    }
    private static boolean positive(String text, String... terms) { return PolicyCandidateMatcher.hasPositiveTerm(text, terms); }
    private static boolean nonMandatory(String text) { return contains(text, "不等于强制", "非强制", "推荐性", "不构成强制"); }
    private static boolean contains(String value, String... words) {
        if (value == null) return false;
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
