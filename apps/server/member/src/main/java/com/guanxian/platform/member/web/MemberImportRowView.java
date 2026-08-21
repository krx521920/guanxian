package com.guanxian.platform.member.web;

import java.util.List;
import java.util.UUID;

public record MemberImportRowView(
        int rowNumber,
        MemberUpsertRequest data,
        List<String> errors,
        String status,
        UUID enterpriseId) {
}
