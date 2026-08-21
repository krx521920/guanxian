package com.guanxian.platform.member.internal;

import com.guanxian.platform.member.web.MemberUpsertRequest;

import java.util.List;
import java.util.UUID;

record MemberImportRow(
        int rowNumber,
        MemberUpsertRequest data,
        List<String> errors,
        String status,
        UUID enterpriseId) {

    MemberImportRow {
        errors = List.copyOf(errors);
    }
}
