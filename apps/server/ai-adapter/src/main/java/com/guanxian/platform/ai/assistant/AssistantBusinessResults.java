package com.guanxian.platform.ai.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;

/** Request-owned, server-authored receipts. Never reconstruct these from model text. */
public final class AssistantBusinessResults {
    public static final String CONTEXT_KEY = "guanxian.businessResults";
    private final List<Result> results = new ArrayList<>();
    public synchronized void add(Result result) {
        if (results.size() >= 8) throw new IllegalStateException("business result budget exceeded");
        results.add(result);
    }
    public synchronized List<Result> snapshot() { return List.copyOf(results); }
    public static void record(ToolContext context, Result result) {
        if (context != null && context.getContext().get(CONTEXT_KEY) instanceof AssistantBusinessResults journal) journal.add(result);
    }
    public static String text(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.length() <= 300 ? text : text.substring(0, 280) + "…（已截短）";
    }
    public record Evidence(String criterion, String state, String field, String observed, String explanation) {}
    public record SourceReference(String kind, String sourceId, String evidenceRecordId, String checkedOn,
                                  @JsonInclude(JsonInclude.Include.ALWAYS) String sourceUrl, List<String> supportingUrls) {
        public SourceReference { supportingUrls = List.copyOf(supportingUrls); }
    }
    public record Item(UUID id, String name, String target, Map<String, String> fields, List<Evidence> evidence,
                       SourceReference source) {
        public Item(UUID id, String name, String target, Map<String, String> fields, List<Evidence> evidence) {
            this(id, name, target, fields, evidence, null);
        }
        public Item { fields = Map.copyOf(fields); evidence = List.copyOf(evidence); }
    }
    public record Result(int schemaVersion, UUID id, String kind, String status, String label,
                         @JsonInclude(JsonInclude.Include.ALWAYS) UUID associationId, String scope, Map<String, String> filters, Instant queriedAt,
                         long total, List<Item> items) {
        public Result { filters = Map.copyOf(filters); items = List.copyOf(items); }
        public static Result create(String kind, String status, String label, UUID associationId,
                                    Map<String, String> filters, long total, List<Item> items) {
            return create(kind, status, label, associationId, filters, total, items, false);
        }
        public static Result create(String kind, String status, String label, UUID associationId,
                                    Map<String, String> filters, long total, List<Item> items, boolean allAssociations) {
            return new Result(1, UUID.randomUUID(), kind, status, label, associationId,
                    allAssociations ? "全部协会（仅限当前账号有权查看的资料）"
                            : "当前账号有权查看的资料；可能包含按可见范围授权的跨协会资料", filters, Instant.now(), total, items);
        }
    }
}
