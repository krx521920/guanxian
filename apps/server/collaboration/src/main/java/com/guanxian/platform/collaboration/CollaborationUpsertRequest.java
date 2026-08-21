package com.guanxian.platform.collaboration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CollaborationUpsertRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 50) List<@NotBlank @Size(max = 200) String> participants,
        @Size(max = 200) String owner,
        @Size(max = 16) String priority,
        @Size(max = 500) String nextAction,
        LocalDate dueDate,
        @Min(0) @Max(100) Integer progress) {
}
