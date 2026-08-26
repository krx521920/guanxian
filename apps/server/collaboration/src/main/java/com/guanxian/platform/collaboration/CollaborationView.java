package com.guanxian.platform.collaboration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CollaborationView(
        UUID id,
        UUID associationId,
        UUID enterpriseId,
        String title,
        List<String> participants,
        String owner,
        String stage,
        String priority,
        String nextAction,
        LocalDate dueDate,
        int progress,
        long version,
        boolean disabled,
        boolean deleted,
        Instant updatedAt) {
    public CollaborationView {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
