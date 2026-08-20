package com.guanxian.platform.policy;

import java.time.LocalDate;
import java.util.List;

public record PolicyView(
        String id,
        String title,
        String authority,
        String level,
        String category,
        LocalDate publishDate,
        LocalDate effectiveDate,
        String status,
        String summary,
        List<String> tags) {
}
