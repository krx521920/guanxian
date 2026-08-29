package com.guanxian.platform.collaboration;

import com.guanxian.platform.shared.security.ActorScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@ConditionalOnProperty(name = "guanxian.business.repository", havingValue = "memory")
class InMemoryCollaborationStore implements CollaborationStore {
    private static final UUID DEMO_ASSOCIATION =
            UUID.fromString("00000000-0000-0000-0000-000000000106");

    private final ConcurrentMap<UUID, CollaborationView> items = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<CollaborationActivityView>> activities = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, List<CollaborationHistoryView>> histories = new ConcurrentHashMap<>();
    private final AtomicLong activitySequence = new AtomicLong();
    private final AtomicLong historySequence = new AtomicLong();

    InMemoryCollaborationStore() {
        this(false);
    }

    @Autowired
    InMemoryCollaborationStore(
            @Value("${guanxian.business.seed-demo-data:${guanxian.member.seed-demo-data:false}}")
            boolean seedDemoData) {
        if (!seedDemoData) {
            return;
        }
        seed("00000000-0000-0000-0000-00000000c001", "高压燃气管道零泄漏阀门联合评估",
                List.of("北京市政建设集团", "北方阀门制造有限公司"), "徐明",
                "IN_PROGRESS", "HIGH", "确认试验场地与技术参数", LocalDate.of(2026, 8, 18), 62);
        seed("00000000-0000-0000-0000-00000000c002", "老旧街区地下管线综合探测需求对接",
                List.of("首都城市更新", "中勘研究院"), "陈晓",
                "OPEN", "MEDIUM", "上传初步勘察方案", LocalDate.of(2026, 8, 21), 38);
        seed("00000000-0000-0000-0000-00000000c003", "监测平台与数字孪生底座联合方案",
                List.of("北方燃气安全", "京城管网"), "王志远",
                "DRAFT", "MEDIUM", "确认双方技术联系人", LocalDate.of(2026, 8, 23), 16);
        seed("00000000-0000-0000-0000-00000000c004", "非开挖修复评价标准案例征集",
                List.of("北京地下管线协会", "北京建工市政"), "张全超",
                "COMPLETED", "LOW", "归档评审意见", LocalDate.of(2026, 8, 12), 100);
    }

    @Override
    public List<CollaborationView> list(
            ActorScope actor, String query, boolean includeDeleted, int offset, int limit) {
        return items.values().stream()
                .filter(item -> includeDeleted || !item.deleted())
                .filter(item -> canRead(actor, item))
                .filter(item -> matches(query, item))
                .sorted(Comparator.comparing(CollaborationView::updatedAt).reversed()
                        .thenComparing(CollaborationView::id))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count(ActorScope actor, String query, boolean includeDeleted) {
        return items.values().stream()
                .filter(item -> includeDeleted || !item.deleted())
                .filter(item -> canRead(actor, item))
                .filter(item -> matches(query, item))
                .count();
    }

    @Override
    public Optional<CollaborationView> find(UUID id, ActorScope actor, boolean includeDeleted) {
        CollaborationView item = items.get(id);
        if (item == null || (!includeDeleted && item.deleted()) || !canRead(actor, item)) {
            return Optional.empty();
        }
        return Optional.of(item);
    }

    @Override
    public boolean canLinkMatch(UUID matchId, UUID associationId, UUID enterpriseId) {
        // The memory adapter has no durable ecosystem catalog and is only enabled explicitly
        // for isolated tests. PostgreSQL performs the authoritative scope check.
        return true;
    }

    @Override
    public synchronized CollaborationView create(
            UUID associationId,
            UUID enterpriseId,
            CollaborationUpsertRequest request,
            ActorScope actor) {
        Instant now = Instant.now();
        CollaborationView value = new CollaborationView(
                UUID.randomUUID(), associationId, enterpriseId, request.matchId(), request.title().trim(),
                cleanList(request.participants()), owner(request.owner(), actor), "DRAFT",
                priority(request.priority()), clean(request.nextAction()), request.dueDate(),
                progress(request.progress()), 0, false, false, now);
        items.put(value.id(), value);
        return value;
    }

    @Override
    public synchronized Optional<CollaborationView> update(
            UUID id,
            long expectedVersion,
            CollaborationUpsertRequest request,
            ActorScope actor) {
        CollaborationView current = items.get(id);
        if (current == null || current.deleted() || current.version() != expectedVersion) {
            return Optional.empty();
        }
        CollaborationView updated = new CollaborationView(
                current.id(), current.associationId(), current.enterpriseId(), request.matchId(), request.title().trim(),
                cleanList(request.participants()), owner(request.owner(), actor), current.stage(),
                priority(request.priority()), clean(request.nextAction()), request.dueDate(),
                progress(request.progress()), current.version() + 1, current.disabled(), false, Instant.now());
        items.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<CollaborationView> transition(
            UUID id,
            long expectedVersion,
            String stage,
            boolean disabled,
            ActorScope actor) {
        CollaborationView current = items.get(id);
        if (current == null || current.deleted() || current.version() != expectedVersion) {
            return Optional.empty();
        }
        int progress = "COMPLETED".equals(stage) ? 100 : current.progress();
        CollaborationView updated = copy(
                current, stage, current.version() + 1, disabled, false, progress);
        items.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<CollaborationView> softDelete(
            UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = items.get(id);
        if (current == null || current.deleted() || current.version() != expectedVersion) {
            return Optional.empty();
        }
        CollaborationView updated = copy(
                current, current.stage(), current.version() + 1, current.disabled(), true, current.progress());
        items.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized Optional<CollaborationView> restore(
            UUID id, long expectedVersion, ActorScope actor) {
        CollaborationView current = items.get(id);
        if (current == null || !current.deleted() || current.version() != expectedVersion) {
            return Optional.empty();
        }
        CollaborationView updated = copy(
                current, "DRAFT", current.version() + 1, false, false, current.progress());
        items.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public CollaborationActivityView appendActivity(
            UUID collaborationId, String type, String detail, ActorScope actor) {
        CollaborationActivityView activity = new CollaborationActivityView(
                activitySequence.incrementAndGet(), type, detail, actor.subject(), Instant.now());
        activities.computeIfAbsent(collaborationId, ignored -> new ArrayList<>());
        synchronized (activities.get(collaborationId)) {
            activities.get(collaborationId).add(activity);
        }
        histories.computeIfAbsent(collaborationId, ignored -> new ArrayList<>());
        synchronized (histories.get(collaborationId)) {
            histories.get(collaborationId).add(new CollaborationHistoryView(
                    historySequence.incrementAndGet(), items.get(collaborationId).version(),
                    "ADD_ACTIVITY", actor.subject(), Map.of("type", type, "detail", detail), Instant.now()));
        }
        return activity;
    }

    @Override
    public List<CollaborationActivityView> activities(UUID collaborationId, int limit) {
        return activities.getOrDefault(collaborationId, List.of()).stream()
                .sorted(Comparator.comparing(CollaborationActivityView::occurredAt).reversed()
                        .thenComparing(Comparator.comparingLong(CollaborationActivityView::id).reversed()))
                .limit(limit)
                .toList();
    }

    @Override
    public List<CollaborationHistoryView> history(UUID collaborationId, int limit) {
        return histories.getOrDefault(collaborationId, List.of()).stream()
                .sorted(Comparator.comparing(CollaborationHistoryView::occurredAt).reversed()
                        .thenComparing(Comparator.comparingLong(CollaborationHistoryView::id).reversed()))
                .limit(limit)
                .toList();
    }

    @Override
    public void recordChange(ActorScope actor, String action, CollaborationView value, String detail) {
        Map<String, Object> snapshot = snapshot(value);
        histories.computeIfAbsent(value.id(), ignored -> new ArrayList<>());
        synchronized (histories.get(value.id())) {
            histories.get(value.id()).add(new CollaborationHistoryView(
                    historySequence.incrementAndGet(), value.version(), action,
                    actor.subject(), snapshot, Instant.now()));
        }
        String activityDetail = detail == null || detail.isBlank()
                ? action + ": " + value.title()
                : detail.trim();
        CollaborationActivityView activity = new CollaborationActivityView(
                activitySequence.incrementAndGet(), action, activityDetail, actor.subject(), Instant.now());
        activities.computeIfAbsent(value.id(), ignored -> new ArrayList<>());
        synchronized (activities.get(value.id())) {
            activities.get(value.id()).add(activity);
        }
    }

    private void seed(
            String id,
            String title,
            List<String> participants,
            String owner,
            String stage,
            String priority,
            String nextAction,
            LocalDate dueDate,
            int progress) {
        CollaborationView item = new CollaborationView(
                UUID.fromString(id), DEMO_ASSOCIATION, null, null, title, participants, owner, stage,
                priority, nextAction, dueDate, progress, 0, false, false, Instant.now());
        items.put(item.id(), item);
    }

    private static boolean canRead(ActorScope actor, CollaborationView value) {
        if (actor.isSystemAdmin()) {
            return true;
        }
        if (actor.associationId() == null || !actor.associationId().equals(value.associationId())) {
            return false;
        }
        if (actor.isAssociationStaff()) {
            return true;
        }
        return value.enterpriseId() == null || value.enterpriseId().equals(actor.enterpriseId());
    }

    private static boolean matches(String query, CollaborationView value) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return value.title().toLowerCase(Locale.ROOT).contains(needle)
                || value.owner() != null && value.owner().toLowerCase(Locale.ROOT).contains(needle)
                || value.participants().stream()
                .anyMatch(item -> item.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static CollaborationView copy(
            CollaborationView old,
            String stage,
            long version,
            boolean disabled,
            boolean deleted,
            int progress) {
        return new CollaborationView(
                old.id(), old.associationId(), old.enterpriseId(), old.matchId(), old.title(), old.participants(),
                old.owner(), stage, old.priority(), old.nextAction(), old.dueDate(), progress,
                version, disabled, deleted, Instant.now());
    }

    private static Map<String, Object> snapshot(CollaborationView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id().toString());
        result.put("title", value.title());
        result.put("matchId", value.matchId() == null ? "" : value.matchId().toString());
        result.put("participants", value.participants());
        result.put("owner", value.owner() == null ? "" : value.owner());
        result.put("stage", value.stage());
        result.put("priority", value.priority());
        result.put("nextAction", value.nextAction() == null ? "" : value.nextAction());
        result.put("dueDate", value.dueDate() == null ? "" : value.dueDate().toString());
        result.put("progress", value.progress());
        result.put("disabled", value.disabled());
        result.put("deleted", value.deleted());
        return result;
    }

    private static List<String> cleanList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String owner(String value, ActorScope actor) {
        String cleaned = clean(value);
        if (cleaned != null) {
            return cleaned;
        }
        return actor.username() == null || actor.username().isBlank() ? actor.subject() : actor.username();
    }

    private static String priority(String value) {
        return value == null || value.isBlank() ? "MEDIUM" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int progress(Integer value) {
        return value == null ? 0 : value;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
