package com.guanxian.platform.policy;

import java.time.Instant;
import java.util.Map;

public record PolicyHistoryView(long version, String action, String actorSubject,
                                Map<String, Object> snapshot, Instant occurredAt) {
}
