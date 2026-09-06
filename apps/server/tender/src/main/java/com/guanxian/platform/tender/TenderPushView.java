package com.guanxian.platform.tender;

import java.time.Instant;
import java.util.UUID;

public record TenderPushView(
        String id,
        UUID tenderId,
        String tenderTitle,
        UUID enterpriseId,
        String enterpriseName,
        String pushedBySubject,
        Instant pushedAt,
        String status,
        long version) {
}
