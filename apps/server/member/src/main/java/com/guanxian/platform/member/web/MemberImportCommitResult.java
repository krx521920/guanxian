package com.guanxian.platform.member.web;

import java.util.List;
import java.util.UUID;

public record MemberImportCommitResult(
        UUID batchId,
        int importedRows,
        int invalidRows,
        List<UUID> enterpriseIds) {
}
