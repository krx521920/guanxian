package com.guanxian.platform.policy;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class PolicyService {
    private final List<PolicyView> policies = List.of(
            new PolicyView(
                    "P001", "城市地下管线建设管理工作指导意见", "住房和城乡建设部", "国家", "建设管理",
                    LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), "即将施行",
                    "强化地下管线全生命周期管理，推动数字化交付与风险分级管控。",
                    List.of("全生命周期", "数字化交付", "风险管理")),
            new PolicyView(
                    "P002", "北京市地下管线信息管理办法（修订）", "北京市城市管理委员会", "北京市", "信息管理",
                    LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 20), "即将施行",
                    "明确管线信息汇交、更新与共享要求，细化建设单位和权属单位责任。",
                    List.of("信息汇交", "数据标准", "权属责任")),
            new PolicyView(
                    "P003", "城镇燃气管网泄漏监测技术导则", "中国城市燃气协会", "行业协会", "安全运行",
                    LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 16), "现行有效",
                    "规定燃气管网泄漏监测系统的建设、运行和数据评价要求。",
                    List.of("燃气", "泄漏监测", "安全")),
            new PolicyView(
                    "P004", "地下管线非开挖修复工程评价标准（征求意见稿）", "北京地下管线协会", "行业协会", "更新改造",
                    LocalDate.of(2026, 8, 8), null, "征求意见",
                    "建立非开挖修复项目的技术、质量与成效评价指标体系。",
                    List.of("非开挖", "修复", "质量评价")));

    public List<PolicyView> findAll(String query) {
        String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (keyword.isBlank()) {
            return policies;
        }
        return policies.stream()
                .filter(policy -> String.join(" ", policy.title(), policy.authority(), policy.category(),
                        policy.summary(), String.join(" ", policy.tags())).toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
    }

    public List<PolicyView> all() {
        return policies;
    }
}
