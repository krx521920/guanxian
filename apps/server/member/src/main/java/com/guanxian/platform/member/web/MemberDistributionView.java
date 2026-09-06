package com.guanxian.platform.member.web;

import java.util.List;

public record MemberDistributionView(
        long total,
        List<DistrictStat> districts,
        List<NamedCount> products,
        List<DistrictStat> categories) {

    public record DistrictStat(String name, long count, double percent) {
    }

    public record NamedCount(String name, long count) {
    }
}
