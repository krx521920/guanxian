package com.guanxian.platform.collaboration;

import java.time.LocalDate;
import java.util.List;

public record CollaborationView(
        String id,
        String title,
        List<String> participants,
        String owner,
        String stage,
        String priority,
        String nextAction,
        LocalDate dueDate,
        int progress) {
}
